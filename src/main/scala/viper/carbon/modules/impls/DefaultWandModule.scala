package viper.carbon.modules.impls


import org.scalatest.GivenWhenThen
import viper.carbon.modules.WandModule
import viper.carbon.modules.components.{TransferComponent, DefinednessComponent}
import viper.carbon.verifier.Verifier
import viper.carbon.boogie._
import viper.carbon.boogie.Implicits._
import viper.carbon.boogie.Namespace

import viper.silver.verifier.{errors, reasons, PartialVerificationError}
import viper.silver.{ast => sil}
/**
 * Created by Gaurav on 06.03.2015.
 */


class DefaultWandModule(val verifier: Verifier) extends WandModule {
  import verifier._
  import stateModule._
  import permModule._

  val transferNamespace = verifier.freshNamespace("transfer")

  /**Gaurav: still need to worry about namespaces/scopes **/
  /** specific to access predicates transfer START**/
/* used to pass as expression in AccessPredicate when transfer of permission needed */
  //val SILneededLocal: sil.LocalVar = sil.LocalVar("neededTransfer")(sil.Perm)
  val SILtempLocal: sil.LocalVar = sil.LocalVar("takeTransfer")(sil.Perm)

  var tempLocal: LocalVar = null // mainModule.env.define(SILtempLocal)

  /* used to track the permissions still needed to transfer */
  val neededLocal = LocalVar(Identifier("neededTransfer")(transferNamespace), permType)

  val permLocal = LocalVar(Identifier("permTransfer")(transferNamespace), permType)

  val curpermLocal = LocalVar(Identifier("maskTransfer")(transferNamespace), permType)
/** specific to access predicates transfer END**/

  val boolTransferUsed = LocalVar(Identifier("accVar1")(transferNamespace), Bool)
  val boolTransferTop = LocalVar(Identifier("accVar2")(transferNamespace), Bool)

  /* Gaurav (03.04.15): this seems nicer as an end result in the Boogie program but null for pos,info params is
   * not really nice
   *   val boogieNoPerm:Exp = permModule.translatePerm(sil.NoPerm()(null,null))
  */
  val boogieNoPerm:Exp = RealLit(0)

  var mainError: PartialVerificationError = null


  //use this to generate unique names for states
  private val names = new BoogieNameGenerator()


  def name = "Wand Module"

  override def initialize() {
    register(this)
  }

  override def transferRemove(e:sil.Exp, cond:Exp): Stmt = {
    Nil
  }

  def transferAdd(e:sil.Exp, cond: Exp): Stmt = {
    Nil
  }

  def transferValid(e:sil.Exp):Seq[(Stmt,Exp)] = {
    Nil
  }

  override def translatePackage(p: sil.Package,error: PartialVerificationError):Stmt = {
    p match {
      case pa@sil.Package(wand) =>
        wand match {
          case w@sil.MagicWand(left,right) =>

            def debugMask = permModule.currentMask(0)  match {
              case GlobalVar(id,typ) => id.name
              case LocalVar(id,typ) => id.name
            }
            //val definedness = expModule.checkDefinedness()
            tempLocal = mainModule.env.define(SILtempLocal) //TODO: find cleaner way to handle this
            mainError = error //set the default error to be used by all operations
            /**DEFINE STATES **/

            val currentState = stateModule.getCopyState

            //hypothetical state for left hand side of wand
            val hypName = names.createUniqueIdentifier("Hypo")
            val (hypStmt,hypState) = stateModule.freshEmptyState("Hypo")

            //state which is used to check if the wand holds
            val usedName = names.createUniqueIdentifier("Used")
            val (usedStmt, usedState) = stateModule.freshEmptyState(usedName)

            /**DEFINE STATES END **/

            //inhale left hand side to initialize hypothetical state
            stateModule.restoreState(hypState)

            val maskBefore = debugMask

            /**create a new boolean variable under which all assumptions belonging to the package are made
              *(which makes sure that the assumptions won't be part of the main state after the package)
              */
            val b = LocalVar(Identifier("b")(transferNamespace), Bool)

            val inhaleLeft =
              MaybeComment("Inhaling left hand side of current wand into hypothetical state",
                exchangeAssumesWithBoolean(stmtModule.translateStmt(sil.Inhale(left)(p.pos,p.info)), b) )

            /*Gaurav: the position and info is taken from the Package node, might be misleading, also
             *the InhaleError will be used and not the package error. Might want to duplicate the translateStmt code
             * for an Inhale node.
             */

            stateModule.restoreState(usedState)
            val stmt = hypStmt ++ usedStmt ++ (b := TrueLit()) ++
                       inhaleLeft ++ exec(hypState :: currentState :: Nil, usedState, right, b)

            val mask1Name = debugMask
            stateModule.restoreState(currentState)

            val mask2Name = debugMask


            Comment("before exec " + maskBefore) ++
            Comment("before restoring " + mask1Name) ++
            Comment("after restoring " + mask2Name) ++ stmt

          //TODO: add wand to current state
        }
    }
  }

  def exec(states: List[StateSnapshot], ops: StateSnapshot, e:sil.Exp, b: LocalVar):Stmt = {
    e match {
      case sil.Folding(acc,body) =>
        sys.error("exec: folding not yet supported")
      case sil.Unfolding(acc,body) =>
        sys.error("exec: unfolding not yet supported")
      case sil.Applying(wand, body) =>
        sys.error("exec: applying not yet supported")
      case sil.Packaging(wand,body) =>
        sys.error("exec: packaging not yet supported")
      case _ =>
        //no ghost operation
        val usedName = names.createUniqueIdentifier("Used")
        val (usedStmt,usedState) = stateModule.freshEmptyState(usedName)
        stateModule.restoreState(usedState)
        usedStmt ++ exhaleExt(ops :: states, usedState,e,b)
    }

  }

  /*generates code that transfers permissions from states to used such that e can be satisfied
    * Precondition: current state is set to the used state
    * */
   def exhaleExt(states: List[StateSnapshot], used:StateSnapshot, e: sil.Exp, b:LocalVar):Stmt = {
    e match {
      case acc@sil.AccessPredicate(_,_) => transferMain(states,used, e, b) //transfer(states, used, acc)
      case acc@sil.MagicWand(_,_) => sys.error("exhaleExtConnective: Magic Wands not yet supported ")
      case acc@sil.And(e1,e2) => exhaleExt(states, used, e1,b) :: exhaleExt(states,used,e2,b) :: Nil
      case acc@sil.Implies(e1,e2) => If(expModule.translateExp(e1), exhaleExt(states,used,e1,b),Statements.EmptyStmt)
      case acc@sil.CondExp(c,e1,e2) => If(expModule.translateExp(c), exhaleExt(states,used,e1,b), exhaleExt(states,used,e1,b))
      case _ => exhaleExtExp(states,used,e,b)
    }

  }

  def exhaleExtExp(states: List[StateSnapshot], used:StateSnapshot, e: sil.Exp, b:LocalVar):Stmt = {
    if(e.isPure) {
      Assert(b ==> expModule.translateExp(e), null)
    } else {
      Statements.EmptyStmt
    }
  }

 /*
  * Precondition: current state is set to the used state
   */
  def transferMain(states: List[StateSnapshot], used:StateSnapshot, e: sil.Exp, b: LocalVar):Stmt = {
    e match {
      case sil.MagicWand(left,right) => sys.error("still need to implement this")
      case p@sil.AccessPredicate(loc,perm)  =>
       // val currentState = stateModule.state //store current state so that it can be restored in the end

        val permAmount = permModule.translatePerm(perm) //store the permission to be transferred into a separate variable
        val positivePerm = Assert(neededLocal >= RealLit(0), mainError.dueTo(reasons.NegativePermission(e)))
        val definedness = MaybeCommentBlock("checking if access predicate defined in used state",
                            expModule.checkDefinedness(p, mainError))

        val rcv_loc: (Exp,Exp) =
                  p match {
                    case loc: sil.FieldAccess => (expModule.translateExp(loc.rcv), heapModule.translateLocation(loc))
                    case _ => sys.error("This version only supports transfer of field access predicates")
                  }

        val s2 = transferAcc(states,used, transformAccessPred(p),b,rcv_loc)
        definedness ++ (permLocal := permAmount) ++ (neededLocal := permAmount) ++  positivePerm ++ s2
      case _ => sys.error("This version only supports access predicates for packaging of wands")
    }
  }

  /**
   * returns the access predicate with the same location as the input access predicate but sets the permission to the
   * local variable "SILneededLocal"
   */
  def transformAccessPred(e: sil.AccessPredicate):sil.AccessPredicate = {
    e match {
      case p@sil.FieldAccessPredicate(loc,perm) => sil.FieldAccessPredicate(loc,SILtempLocal)(p.pos,p.info)
      case p@sil.PredicateAccessPredicate(loc,perm) => sil.PredicateAccessPredicate(loc,SILtempLocal)(p.pos,p.info)
    }
  }

  /*
   * Precondition: current state is set to the used state
    */
  def transferAcc(states: List[StateSnapshot], used:StateSnapshot, e: sil.AccessPredicate, b: LocalVar, rcv_loc: (Exp,Exp)):Stmt = {
    states match {
      case (top :: xs) =>
        val addToUsed = components flatMap (_.transferAdd(e,b))
        val equateExpr = equate(top,e.loc)

        //check definedness of e in state x
        stateModule.restoreState(top)
        val definednessTop:Stmt = Havoc(boolTransferTop) ++ (boolTransferTop := TrueLit()) ++
          (generateStmtCheck(components flatMap (_.transferValid(e)), boolTransferTop))
        val minStmt = If(neededLocal <= curpermLocal, tempLocal := neededLocal, tempLocal := curpermLocal)
        val curPermTop = permModule.currentPermission(rcv_loc._1, rcv_loc._2)
        val removeFromTop = components flatMap (_.transferRemove(e,b))

        stateModule.restoreState(used)

        If(boolTransferTop && neededLocal > boogieNoPerm,
          (curpermLocal := curPermTop) ++
            minStmt ++
            If(tempLocal > boogieNoPerm,

              (neededLocal := neededLocal - tempLocal) ++
              addToUsed ++
              (b := b && equateExpr) ++
              removeFromTop,

              Nil),

            Nil
        )
      case Nil =>
        val curPermUsed = permModule.currentPermission(rcv_loc._1, rcv_loc._2)
        Assert(b ==> neededLocal === boogieNoPerm && curPermUsed === permLocal, mainError.dueTo(reasons.InsufficientPermission(e.loc)))

        /**
         * actually only curPermUsed === permLocal would be sufficient if the transfer is written correctly, since
         * the two conjuncts should be equivalent in theory, but to be safe we ask for the conjunction to hold
         * in case of bugs
         * */
    }
  }

  //equate loc in current state with loc in state s (current.equate(s,loc))
  def equate(s: StateSnapshot, loc: sil.LocationAccess): Exp = {
    val lhs = heapModule.translateLocation(loc)
    val current = stateModule.state
    stateModule.restoreState(s)
    val rhs = heapModule.translateLocation(loc)
    stateModule.restoreState(current)
    (lhs === rhs)
  }


   /*
    *transforms a statement such that it doesn't have any explicit assumptions anymore and all assumptions
    * are accumulated in a single variable, i.e.:
    *
    * Assume x != null
    * Assume y >= 0 is transformed to:
    * b := (b && x!= null); b := (b && y >= 0);
    */
  def exchangeAssumesWithBoolean(stmt: Stmt,boolVar: LocalVar):Stmt = {
    stmt match {
      case Assume(exp) =>
        boolVar := (boolVar && exp)
      case Seqn(statements) =>
       Seqn(statements.map(s => exchangeAssumesWithBoolean(s, boolVar)))
      case If(c,thn,els) =>
        If(c,exchangeAssumesWithBoolean(thn,boolVar),exchangeAssumesWithBoolean(els,boolVar))
      case NondetIf(thn,els) =>
        NondetIf(exchangeAssumesWithBoolean(thn,boolVar))
      case CommentBlock(comment,s) =>
        CommentBlock(comment, exchangeAssumesWithBoolean(s,boolVar))
      case s => s
    }
  }

  /*
   * Let the argument be a sequence [(s1,e1),(s2,e2)].
   * Then the function returns:
   * (s1; b := b&&e1; s2; b := b&&e2)
   */
  def generateStmtCheck(x: Seq[(Stmt,Exp)], boolVar:LocalVar):Stmt = {
    (x map (y => y._1 ++ (boolVar := boolVar&&y._2))).flatten
  }
}
