package org.protocols.paxos

import akka.actor.ActorRef

import scala.collection.immutable.Nil

/**
  * @author Ilya Sergey
  */


trait PaxosRoles[T] extends PaxosVocabulary[T] {

  type ToSend = Seq[(ActorRef, PaxosMessage)]
  type Step = PartialFunction[Any, ToSend]

  /**
    * A generic interface for Paxos roles
    */
  sealed trait PaxosRole {
    // Abstract members to be initialized
    protected val self: ActorRef

    protected def initStepHandler: Step

    def step: Step = currentStepFunction

    // Adapt the message for the wrapping combinator
    protected def emitOne(a: ActorRef, msg: PaxosMessage) = Seq((a, msg))

    protected def emitMany(as: Seq[ActorRef], f: ActorRef => PaxosMessage): ToSend = as.zip(as.map(a => f(a)))

    protected def emitZero: ToSend = Seq.empty

    protected def become(r: Step) {
      currentStepFunction = r
    }

    private var currentStepFunction: Step = initStepHandler
  }

  /** ***************************************************************/
  /** *********** Specific roles within the Paxos protocol **********/
  /** ***************************************************************/


  ////////////////////////////////////////////////////////////////////
  //////////////////////       Acceptor      /////////////////////////
  ////////////////////////////////////////////////////////////////////

  abstract class AcceptorRole(val myStartingBallot: Int = -1) extends PaxosRole {

    var currentBallot: Ballot = myStartingBallot
    var chosenValues: List[(Ballot, T)] = Nil

    //    def getLastChosenValue: Option[T] = findMaxBallotAccepted(chosenValues)

    // This method is _always_ safe to run, as it only reduces the set of Acceptor's behaviors
    def bumpUpBallot(b: Ballot): Unit = {
      if (b > currentBallot) {
        currentBallot = b
      }
    }


    final override def initStepHandler: Step = {
      case Phase1A(b, l) =>
        // Using non-strict inequality here for multi-paxos
        if (b >= currentBallot) {
          bumpUpBallot(b)
          emitOne(l, Phase1B(promise = true, self, findMaxBallotAccepted(chosenValues)))
        } else {
          emitZero
        }
      case Phase2A(b, l, v) =>
        if (b == currentBallot) {
          // record the value
          chosenValues = (b, v) :: chosenValues
          // we may even ignore this step
          emitOne(l, Phase2B(b, self, ack = true))
        } else {
          emitZero
        }
      // Send accepted request
      case QueryAcceptor(sender) =>
        emitOne(sender, ValueAcc(self, findMaxBallotAccepted(chosenValues).map(_._2)))
    }
  }


  ////////////////////////////////////////////////////////////////////
  //////////////////////       Proposer      /////////////////////////
  ////////////////////////////////////////////////////////////////////

  abstract class ProposerRole(val acceptors: Seq[ActorRef], val myBallot: Ballot) extends PaxosRole {

    final override def initStepHandler: Step = proposerInit

    type Responses = List[(ActorRef, Option[(Ballot, T)])]
    private var quorum: Option[Responses] = None

    def setQuorum(rs: Responses) {
      quorum = Some(rs)
    }

    def proposerInit: Step = {
      case ProposeValue(v) =>
        // Start Paxos round with my given ballot
        become(proposerCollectForQuorum(v, Nil))
        emitMany(acceptors, _ => Phase1A(myBallot, self))
    }

    def proposerCollectForQuorum(v: T, responses: List[(ActorRef, Option[(Ballot, T)])]): Step = {
      case Phase1B(true, a, vOpt) =>
        val newResponses = (a, vOpt) :: responses
        // find maximal group of accepted values
        if (newResponses.size > acceptors.size / 2) {
          // Got the quorum
          setQuorum(newResponses)
          proceedWithQuorum(v)
          // Enter the final stage
        } else {
          become(proposerCollectForQuorum(v, newResponses))
          emitZero
        }
    }

    /**
      * This method is a point-cut to short-circuit the `proposerCollectForQuorum` stage
      *
      * @param v value to be proposed
      * @return messages to be sent to the acceptors
      */
    def proceedWithQuorum(v: T): ToSend = {
      if (quorum.isEmpty || step == finalStage ||
          quorum.get.size <= acceptors.size / 2) {
        throw new Exception("No quorum has been reached, or the proposer is no longer active")
      }
      // found quorum
      val nonEmptyResponses = quorum.get.map(_._2).filter(_.nonEmpty)
      val toPropose: T = nonEmptyResponses match {
        case Nil => v
        case rs => rs.map(_.get).maxBy(_._1)._2 // A highest-ballot proposal
      }
      val quorumRecipients = quorum.get.map(_._1)
      become(finalStage)
      emitMany(quorumRecipients, _ => Phase2A(myBallot, self, toPropose))
    }

    // Starting now we only respond to queries about selected values
    def finalStage: Step = new PartialFunction[Any, ToSend] {
      override def isDefinedAt(x: Any): Boolean = false
      override def apply(v1: Any): ToSend = emitZero
    }

  }

  ////////////////////////////////////////////////////////////////////
  //////////////////////       Learner       /////////////////////////
  ////////////////////////////////////////////////////////////////////

  abstract class LearnerRole(val acceptors: Seq[ActorRef]) extends PaxosRole {

    final override def initStepHandler: Step = waitForQuery

    def waitForQuery: Step = {
      case QueryLearner(sender) =>
        become(respondToQuery(sender, Nil))
        emitMany(acceptors, _ => QueryAcceptor(self))
      case ValueAcc(_, _) => emitZero // ignore this now, as it's irrelevant
    }

    private def respondToQuery(sender: ActorRef,
                               results: List[Option[T]]): Step = {
      case ValueAcc(a, vOpt) =>
        val newResults = vOpt :: results
        val maxGroup = newResults.groupBy(x => x).toSeq.map(_._2).maxBy(_.size)

        if (maxGroup.nonEmpty && maxGroup.size > acceptors.size / 2) {
          become(waitForQuery)
          if (maxGroup.head.isEmpty) {
            // No consensus has been reached so far, repeat the procedure from scratch
            emitOne(self, QueryLearner(sender))
          } else {
            // respond to the sender
            emitOne(sender, LearnedAgreedValue(maxGroup.head.get, self))
          }
        } else {
          become(respondToQuery(sender, newResults))
          emitZero
        }
    }
  }


}
