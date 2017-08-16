package org.protocols.paxos.register

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.{Actor, ActorSystem}
import org.protocols.paxos.combinators.MessageForSlot
import scala.collection.mutable.{Map => MMap}

/**
  * @author Ilya Sergey
  */

class SlotReplicatingRegisterProvider[T](override val system: ActorSystem, override val numA: Int)
    extends RoundRegisterProvider[T](system, numA) {

  type Slot = Int

  class SlotReplicatingAcceptor extends Actor {
    private val slotMachineMap: MMap[Slot, AcceptorForRegister] = MMap.empty

    protected def getMachineForSlot(slot: Slot): AcceptorForRegister = {
      slotMachineMap.get(slot) match {
        case Some(role) => role
        case None =>
          val role = new AcceptorForRegister(self)
          slotMachineMap.update(slot, role)
          role
      }
    }

    protected def getAllMachines: Map[Slot, AcceptorForRegister] =
      (for (s <- slotMachineMap.keys) yield (s, getMachineForSlot(s))).toMap

    override def receive: Receive = {
      case RegisterMessageForSlot(slot, incoming)
        if getMachineForSlot(slot).step.isDefinedAt(incoming) =>
        val toSend = getMachineForSlot(slot).step(incoming)
        val dst = toSend.dest
        dst ! RegisterMessageForSlot(slot, toSend)
    }
  }

  class SlotReplicatingRegisterProxy(msgQueue: ConcurrentLinkedQueue[Any], params: Seq[Any]) extends Actor {
    val mySlot = params.head.asInstanceOf[Slot]

    def receive: Receive = {
      // Incoming message
      case rms@RegisterMessageForSlot(slot, msg: RegisterMessage) if msg.dest == self =>
        msgQueue.add(msg)
      // Outgoing message
      case msg: RegisterMessage =>
        msg.dest ! RegisterMessageForSlot(mySlot, msg)
      case _ =>
    }

  }

  // Instantiate the middleware
  val AcceptorClass: Class[_] = classOf[SlotReplicatingAcceptor]
  val RegisterProxyClass: Class[_] = classOf[SlotReplicatingRegisterProxy]
}

case class RegisterMessageForSlot[+M](slot: Int, msg: M)