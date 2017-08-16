package org.protocols.register.multipaxos

import org.protocols.register.RoundRegisterProvider

/**
  * @author Ilya Sergey
  */

class CartesianRegisterBasedMultiPaxosTests extends GenericRegisterBasedMultiPaxosTests {

  def getRegisterProvider(numAcceptors: Int): RoundRegisterProvider[String] = {
    new SlotReplicatingRegisterProvider[String](_system, numAcceptors)
  }

}
