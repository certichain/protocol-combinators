package org.protocols.paxos.singledecree

import akka.actor.{Actor, ActorRef}
import org.protocols.paxos.PaxosVocabulary

import scala.collection.immutable.Nil

/**
  * @author Ilya Sergey
  */

trait SingleDecreePaxos[T] {
  // Instantiate messages
  val MessageModule = new PaxosVocabulary[T]

  import MessageModule._

  class Acceptor extends Actor {

    var currentBallot: Ballot = -1
    var chosenValues: List[(Ballot, T)] = Nil

    def findMaxBallotAccepted: Option[T] = chosenValues match {
      case Nil => None
      case x => Some(x.maxBy(_._1)._2)
    }

    def getChosenValue: Option[T] = findMaxBallotAccepted

    override def receive: Receive = {
      case Phase1A(b, l) =>
        if (b > currentBallot) {
          currentBallot = b
          l ! Phase1B(promise = true, self, findMaxBallotAccepted)
        } else {
          /* do nothing */
        }
      case Phase2A(b, l, v) =>
        if (b == currentBallot) {
          // record the value
          chosenValues = (b, v) :: chosenValues
          // we may even ignore this step
          l ! Phase2B(b, self, ack = true)
        } else {
          /* do nothing */
        }

      // Send accepted request
      case QueryAcceptor(sender) =>
        sender ! AgreedValueAcc(self, getChosenValue)
    }
  }

  class Proposer(val acceptors: Set[ActorRef], val myBallot: Ballot) extends Actor {

    def initReceive: Receive = {
      case ProposeValue(v) =>
        // Start Paxos round with my givenballot
        for (a <- acceptors) a ! Phase1A(myBallot, self)
        context.become(proposerMainPhase(v, Nil))
    }

    def proposerMainPhase(v: T, responses: List[(ActorRef, Option[T])]): Receive = {
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

          for (a <- quorum) a ! Phase2A(myBallot, self, toPropose)
          context.become(finalStage)
        } else {
          context.become(proposerMainPhase(v, newResponses))
        }
    }

    /**
      * Now we only respond to queries about selected values
      */
    def finalStage: Receive = {
      case QueryProposer(sender) =>
        for (a <- acceptors) a ! QueryAcceptor(self)
        context.become(respondToQuery(sender, Nil))
    }

    def respondToQuery(sender: ActorRef,
                       results: List[Option[T]]): Receive = {
      case AgreedValueAcc(a, vOpt) =>
        val newResults = vOpt :: results
        val maxGroup = newResults.groupBy(x => x).toSeq.map(_._2).maxBy(_.size)

        if (maxGroup.nonEmpty && maxGroup.size > acceptors.size / 2) {
          // respond to the sender
          sender ! AgreedValueProposer(maxGroup.head)
          context.become(finalStage)
        } else {
          context.become(respondToQuery(sender, newResults))
        }
    }

    override def receive: Receive = initReceive
  }

  // TODO: implement a factory method for starting the Paxos
  // and returning the interface object to the client

}
