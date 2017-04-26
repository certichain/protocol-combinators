package org.protocols.paxos

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorRef}

import scala.collection.immutable.Nil

/**
  * @author Ilya Sergey
  */


trait PaxosRoles[T] extends PaxosVocabulary[T] {

  sealed trait PaxosRole {
    // Abstract members to be initialized
    val self: ActorRef
    def initReceiveHandler: Receive
    def wrapMsg: PaxosMessage => Any

    //
    private var currentReceiveHandler: Receive = initReceiveHandler
    def become(r: Receive) { currentReceiveHandler = r }
    def receiveHandler: Receive = currentReceiveHandler
    // Adapt the message for the wrapping combinator
    def wrapSend(a: ActorRef, msg: PaxosMessage) { a ! wrapMsg(msg) }
  }

  /** ***************************************************************/
  /** *********** Specific roles within the Paxos protocol **********/
  /** ***************************************************************/

  abstract class AcceptorRole(val wrapMsg: PaxosMessage => Any) extends PaxosRole {

    var currentBallot: Ballot = -1
    var chosenValues: List[(Ballot, T)] = Nil

    final override def initReceiveHandler: Receive = {
      case Phase1A(b, l) =>
        if (b > currentBallot) {
          currentBallot = b
          wrapSend(l, Phase1B(promise = true, self, findMaxBallotAccepted(chosenValues)))
        } else {
          /* do nothing */
        }
      case Phase2A(b, l, v) =>
        if (b == currentBallot) {
          // record the value
          chosenValues = (b, v) :: chosenValues
          // we may even ignore this step
          wrapSend(l, Phase2B(b, self, ack = true))
        } else {
          /* do nothing */
        }
      // Send accepted request
      case QueryAcceptor(sender) =>
        wrapSend(sender, ValueAcc(self, findMaxBallotAccepted(chosenValues)))
    }
  }


  abstract class ProposerRole(val acceptors: Seq[ActorRef], val myBallot: Ballot,
                              val wrapMsg: PaxosMessage => Any) extends PaxosRole {

    final override def initReceiveHandler: Receive = proposerPhase1

    def proposerPhase1: Receive = {
      case ProposeValue(v) =>
        // Start Paxos round with my givenballot
        for (a <- acceptors) wrapSend(a, Phase1A(myBallot, self))
        become(proposerPhase2(v, Nil))
    }

    def proposerPhase2(v: T, responses: List[(ActorRef, Option[T])]): Receive = {
      case Phase1B(true, a, vOpt) =>
        val newResponses = (a, vOpt) :: responses
        // find maximal group
        val maxGroup = newResponses.groupBy(_._2).toList.map(_._2).maxBy(_.size)
        if (maxGroup.nonEmpty && maxGroup.size > acceptors.size / 2) {
          // found quorum
          val toPropose = maxGroup.head._2 match {
            case Some(w) => w
            case None => v
          }
          val quorum = maxGroup.map(_._1)

          for (a <- quorum) wrapSend(a, Phase2A(myBallot, self, toPropose))
          become(finalStage)
        } else {
          become(proposerPhase2(v, newResponses))
        }
    }

    /**
      * Now we only respond to queries about selected values
      */
    def finalStage: Receive = new PartialFunction[Any, Unit] {
      override def isDefinedAt(x: Any): Boolean = false
      override def apply(v1: Any): Unit = {}
    }

  }


  abstract class LearnerRole(val acceptors: Seq[ActorRef], val wrapMsg: PaxosMessage => Any) extends PaxosRole {

    final override def initReceiveHandler: Receive = waitForQuery

    def waitForQuery: Receive = {
      case QueryLearner(sender) =>
        for (a <- acceptors) wrapSend(a, QueryAcceptor(self))
        val rq = respondToQuery(sender, Nil)
        become(rq)
      case ValueAcc(_, _) => // ignore this now, as it's irrelevant
    }

    private def respondToQuery(sender: ActorRef,
                               results: List[Option[T]]): Receive = {
      case ValueAcc(a, vOpt) =>
        val newResults = vOpt :: results
        val maxGroup = newResults.groupBy(x => x).toSeq.map(_._2).maxBy(_.size)

        if (maxGroup.nonEmpty && maxGroup.size > acceptors.size / 2) {
          if (maxGroup.head.isEmpty) {
            // No consensus has been reached so far, repeat the procedure from scratch
            wrapSend(self, QueryLearner(sender))
          } else {
            // respond to the sender
            wrapSend(sender, LearnedAgreedValue(maxGroup.head.get, self))
          }
          become(waitForQuery)
        } else {
          become(respondToQuery(sender, newResults))
        }
    }
  }


}
