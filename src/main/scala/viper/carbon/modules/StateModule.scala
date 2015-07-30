/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.carbon.modules

import components.{StateComponent, ComponentRegistry}
import viper.silver.{ast => sil}
import viper.carbon.boogie.{LocalVarDecl, Exp, Stmt}

/**
 * A module for dealing with the state of a program during execution.  Allows other modules
 * to register [[viper.carbon.modules.components.StateComponent]]s that contribute to the
 * state.

 */
trait StateModule extends Module with ComponentRegistry[StateComponent] {

  /**
   * Returns an assumption that the current state is 'good', or well-formed.
   */
  def assumeGoodState: Stmt

  /**
   * Returns a static invocation of the 'is good state' function with the arguments from
   * `stateContributions`.
   */
  def staticGoodState: Exp

  /**
   * The statements necessary to initialize the part of the state belonging to this module.
   */
  def initState: Stmt

  /**
   * The statements necessary to reset the part of the state belonging to this module.
   */
  def resetState: Stmt

  /**
   * The statements necessary to initialize old(state).
   */
  def initOldState: Stmt

  /**
   * The name and type of the contribution of this components to the state.
   */
  def stateContributions: Seq[LocalVarDecl]

  /**
   * The current values for this components state contributions.  The number of elements
   * in the list and the types must correspond to the ones given in `stateContributions`.
   */
  def currentStateContributions: Seq[Exp]

  type StateSnapshot

  /**
   * Backup the current state and return enough information such that it can
   * be restored again at a later point.
   *
   */
  def freshTempState(name: String): (Stmt, StateSnapshot)

  /**
   * Replace the current state with a given snapshot.
   */
  def replaceState(snapshot: StateSnapshot)

  /**
   * Get the current old state.
   */
  def oldState: StateSnapshot

  /**
   * Replace the old state with a given snapshot.
   */
  def replaceOldState(snapshot: StateSnapshot)

  /**
   * Get the current state in use.
   */
  def state: StateSnapshot

  /**
   * Are we currently using an 'old' state? Implies that we should wrap relevant state components in "Old"
   */
  def stateModuleIsUsingOldState: Boolean
}
