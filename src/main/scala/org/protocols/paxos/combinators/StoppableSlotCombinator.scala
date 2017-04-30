package org.protocols.paxos.combinators

import akka.actor.ActorRef
import org.protocols.paxos.PaxosRoles

import scala.collection.Map

/**
  * A combinator for stoppable functionality
  *
  * @author Ilya Sergey
  */

trait StoppableSlotCombinator[T] extends BunchingSlotCombinator[DataOrStop[T]] with PaxosRoles[DataOrStop[T]] {

  /**
    * For stoppable functionality, we only need to change the proposer logic, not the acceptors
    *
    * The trick is to perform some extra analysis on the structure of our proposers,
    * and then post-process the messages accordingly.
    *
    */
  class StoppableProposerActor(override val acceptors: Seq[ActorRef], override val myBallot: Ballot)
      extends ProposerBunchingActor(acceptors, myBallot) {

    // Analyse the output of the proposer in order to decide whether to forward it or not
    override def postProcess(i: Slot, toSend: ToSend): ToSend = toSend match {
      // Only trigger if we're dealing with the Phase2A message
      case p2as@((_, Phase2A(_, _, data, mbal_i)) :: _) =>

        // Simple sanity check
        assert(p2as.forall(_._2.isInstanceOf[Phase2A]), s"All messages should be Phase2A:\n$p2as")
        val (_, Phase2A(_, _, data, _)) = p2as.head // all other are identical

        // Get slot/proposal information
        val slotToProposedVal: Map[Slot, (Option[DataOrStop[T]], Ballot)] =
          (getAllMachines - i).map {
            case (s, p) =>
              val (dOpt, c, _) = p.val2a
              // See [Gratuitous cancellations]
              val r = if (p.hasProposed) (dOpt, c) else (None, -1)
              (s, r)
          }

        // Now the most interesting stage: decide whether we can send `stop`
        data match {
          // Only forward the messages if there is no preceding stop command
          case Data(d) =>
            val earlierStop = slotToProposedVal.exists {
              // All slots j < i are not stop commands
              case (j, (vOpt, mbal_j)) => j < i && vOpt.isDefined && vOpt.get.isStop
            }
            if (earlierStop) createVoidMessages(p2as, "Data (Earlier Stop)") else p2as

          // Decide whether we can emit stop given our accumulated record for slots
          case Stop(s) =>
            val shouldVoidStop = slotToProposedVal.exists {
              // A condition from Stoppable Paxos
              case (j, (vOpt, mbal_j)) => j > i && mbal_j >= mbal_i
            }
            // Void the command if there are later non-stops
            if (shouldVoidStop) createVoidMessages(p2as, "Stop (Later Data)") else p2as
          case _ => Nil
        }
      case xs => xs
    }
  }

  def createVoidMessages(ps: Seq[(ActorRef, PaxosMessage)], reason: String) =
    ps.asInstanceOf[Seq[(ActorRef, Phase2A)]].map {
      case (a, Phase2A(mb, p, _, mbal)) => (a, Phase2A(mb, p, Voided(reason), mbal))
    }

}

/* [Gratuitous cancellations]

It's important to differentiate between values that have been
already proposed and are only planned to be proposed,
in order to avoid gratuitous self-cancellation between stop and data

Hmm... It seems that due to bunching the effects in ProposerBunchingActor,
we cannot avoid self-cancellation. I wonder whether it's too bad,
as the only option is to sequentialize the updates of `hasProposed`, but this
leads to non-compositional construction
  */

/**
  * A class for identifying data/stop/void command
  */
abstract sealed class DataOrStop[+M] {
  def isStop: Boolean
}

case class Data[M](data: M) extends DataOrStop[M] {
  override def isStop: Boolean = false
}

case class Stop(id: String) extends DataOrStop[Nothing] {
  override def isStop: Boolean = true
}

// Better than just emitting nothing
case class Voided(reason: String) extends DataOrStop[Nothing] {
  override def toString: String = s"[Voided $reason]"
  override def isStop: Boolean = false
}
