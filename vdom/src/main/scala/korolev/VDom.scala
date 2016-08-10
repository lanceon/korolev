package korolev

import scala.annotation.tailrec

trait VDom

object VDom {
  
  trait NodeLike extends VDom {
    def needReplace(b: NodeLike): Boolean
  }

  case class Node(tag: String,
                  children: List[NodeLike],
                  attrs: List[Attr],
                  misc: List[Misc])
      extends VDom
      with NodeLike {
    def needReplace(b: NodeLike): Boolean = b match {
      case el: Node => tag != el.tag
      case _ => true
    }
  }

  case class Text(value: String) extends VDom with NodeLike {
    def needReplace(b: NodeLike): Boolean = b match {
      case el: Text => value != el.value
      case _ => true
    }
  }

  trait Misc extends VDom

  case class Attr(name: String, value: Any, isProperty: Boolean = true)
      extends VDom
  case class NodeLikes(xs: List[NodeLike]) extends VDom
  case object Empty extends VDom

  case class Id(vec: Vector[Int]) {
    def +(x: Int) = Id(vec :+ x)
    def ++(x: Id) = Id(vec ++ x.vec)
    override def toString = vec.mkString("_")
  }

  object Id {
    def apply(x: Int): Id = new Id(Vector(x))
    implicit val idOrdering = new Ordering[Id] {
      val int = implicitly[Ordering[Int]]
      def compare(x: Id, y: Id): Int = {
        if (x.vec.length < y.vec.length) -1
        else if (x.vec.length > y.vec.length) 1
        else {
          // x.length == y.length
          val l = x.vec.length
          @tailrec def loop(i: Int): Int = {
            if (i == l) 0 else {
              val res = int.compare(x.vec(i), y.vec(i))
              if (res == 0) loop(i + 1)
              else res
            }
          }
          loop(0)
        }
      }
    }
  }


  sealed trait Change {
    def orderField: Id
  }

  object Change {
    sealed trait NodeChange
    sealed trait NotNodeChange

    trait OrderById {
      def id: Id
      lazy val orderField = id
    }

    trait OrderByIdAndChildId {
      def id: Id
      def childId: Id
      lazy val orderField = id ++ childId
    }

    case class Create(id: Id, childId: Id, tag: String)
        extends Change
        with OrderByIdAndChildId with NodeChange
    case class CreateText(id: Id, childId: Id, text: String)
        extends Change
        with OrderByIdAndChildId with NodeChange
    case class Remove(id: Id, childId: Id)
        extends Change
        with OrderByIdAndChildId with NodeChange

    case class SetAttr(
        id: Id, name: String, value: Any, isProperty: Boolean)
        extends Change
        with OrderById with NotNodeChange
    case class RemoveAttr(id: Id, name: String, isProperty: Boolean)
        extends Change
        with OrderById with NotNodeChange

    case class CreateAbstractNode(id: Id, childId: Id, node: NodeLike)
        extends Change
        with OrderByIdAndChildId with NodeChange

    case class AddMisc(id: Id, value: Misc) extends Change with OrderById  with NotNodeChange

    case class RemoveMisc(id: Id, value: Misc)
        extends Change
        with OrderById  with NotNodeChange

    implicit val changeOrdering = new Ordering[Change] {
      def compare(x: Change, y: Change): Int = (x, y) match {
        case (_: NodeChange, _: NotNodeChange) => -1
        case (_: NotNodeChange, _: NodeChange) => 1
        case _ => Id.idOrdering.compare(x.orderField, y.orderField)
      }
    }
  }

  type Changes = List[Change]

  private case class ChangesLoopCtx(curr: Id,
                                    i: Int,
                                    as: List[NodeLike],
                                    bs: List[NodeLike],
                                    prev: Option[ChangesLoopCtx])

  private case class ElToChangesCtx(curr: Id,
                                    i: Int,
                                    appendAfter: Change,
                                    tl: List[NodeLike],
                                    prev: Option[ElToChangesCtx])

  def changes(a: NodeLike, b: NodeLike): Changes = {

    import Change._

    @tailrec
    def changesBetweenMisc(
        id: Id, acc: Changes, as: List[Misc], bs: List[Misc]): Changes = {
      (as, bs) match {
        case (Nil, Nil) => acc
        case (ax :: asTl, Nil) =>
          changesBetweenMisc(id, AddMisc(id, ax) :: acc, asTl, Nil)
        case (Nil, bx :: bsTl) =>
          changesBetweenMisc(id, RemoveMisc(id, bx) :: acc, Nil, bsTl)
        case (ax :: asTl, bx :: bsTl) if ax != bx =>
          val newAcc = RemoveMisc(id, ax) :: AddMisc(id, bx) :: acc
          changesBetweenMisc(id, newAcc, asTl, bsTl)
        case (_ :: asTl, _ :: bsTl) =>
          changesBetweenMisc(id, acc, asTl, bsTl)
      }
    }

    @tailrec
    def miscToChanges(id: Id, acc: Changes, misc: List[Misc]): Changes = {
      misc match {
        case Nil => acc
        case x :: xs => miscToChanges(id, AddMisc(id, x) :: acc, xs)
      }
    }
    @tailrec
    def elToChanges(curr: Id,
                    i: Int,
                    acc: Changes,
                    tl: List[NodeLike],
                    ctx: Option[ElToChangesCtx]): Changes = {
      lazy val id = curr + i
      tl match {
        case Nil =>
          ctx match {
            case Some(
                ElToChangesCtx(oldCurr, oldI, appendAfter, oldTl, oldCtx)) =>
              elToChanges(oldCurr, oldI, appendAfter :: acc, oldTl, oldCtx)
            case None => acc
          }
        case Node(tag, Nil, attrs, misc) :: xs =>
          val withAttrsAndMisc = miscToChanges(
              id, attrToChanges(id, acc, attrs), misc)
          elToChanges(
              curr, i + 1, Create(curr, id, tag) :: withAttrsAndMisc, xs, ctx)
        case Node(tag, children, attrs, misc) :: xs =>
          elToChanges(
              id,
              0,
              miscToChanges(id, attrToChanges(id, acc, attrs), misc),
              children,
              Some(ElToChangesCtx(curr, i + 1, Create(curr, id, tag), xs, ctx))
          )
        case Text(value) :: xs =>
          elToChanges(curr, i + 1, CreateText(curr, id, value) :: acc, xs, ctx)
        case node :: xs =>
          elToChanges(
              curr, i + 1, CreateAbstractNode(curr, id, node) :: acc, xs, ctx)
      }
    }

    @tailrec
    def attrToChanges(id: Id, acc: Changes, attrs: List[Attr]): Changes = {
      attrs match {
        case Nil => acc
        case Attr(name, value, isProperty) :: xs =>
          attrToChanges(id, SetAttr(id, name, value, isProperty) :: acc, xs)
      }
    }

    @tailrec
    def changesBetweenAttrs(
        id: Id, acc: Changes, as: List[Attr], bs: List[Attr]): Changes = {
      (as, bs) match {
        case (Nil, Nil) => acc
        case (Nil, bx :: bstl) =>
          val change = SetAttr(id, bx.name, bx.value, bx.isProperty)
          changesBetweenAttrs(id, change :: acc, Nil, bstl)
        case (ax :: astl, Nil) =>
          val change = RemoveAttr(id, ax.name, ax.isProperty)
          changesBetweenAttrs(id, change :: acc, astl, Nil)
        case (ax :: astl, bx :: bstl)
            if ax.name != bx.name || ax.isProperty != bx.isProperty =>
          val newAcc =
            RemoveAttr(id, ax.name, ax.isProperty) :: SetAttr(
                id, bx.name, bx.value, bx.isProperty) :: acc
          changesBetweenAttrs(id, newAcc, astl, bstl)
        case (ax :: astl, bx :: bstl) if ax.value != bx.value =>
          val change = SetAttr(id, ax.name, bx.value, bx.isProperty)
          changesBetweenAttrs(id, change :: acc, astl, bstl)
        case (ax :: astl, bx :: bstl) =>
          changesBetweenAttrs(id, acc, astl, bstl)
      }
    }

    @tailrec
    def changesLoop(curr: Id,
                    i: Int,
                    acc: Changes,
                    as: List[NodeLike],
                    bs: List[NodeLike],
                    ctx: Option[ChangesLoopCtx]): Changes = {
      lazy val id = curr + i
      (as, bs) match {
        case (Nil, Nil) =>
          ctx match {
            case Some(ChangesLoopCtx(oldCurr, oldi, astl, bstl, oldCtx)) =>
              changesLoop(oldCurr, oldi, acc, astl, bstl, oldCtx)
            case None => acc
          }
        case (Nil, bx :: bstl) =>
          val create = elToChanges(curr, i, acc, List(bx), None)
          changesLoop(curr, i + 1, create, Nil, bstl, ctx)
        case (ax :: astl, Nil) =>
          val change = Remove(curr, id)
          changesLoop(curr, i + 1, change :: acc, astl, Nil, ctx)
        case (ax :: astl, bx :: bstl) if ax.needReplace(bx) =>
          val create = elToChanges(curr, i, acc, List(bx), None)
          changesLoop(curr, i + 1, create, Nil, bstl, ctx)
        case ((ax: Node) :: astl, (bx: Node) :: bstl) =>
          val attrChanges = changesBetweenAttrs(id, acc, ax.attrs, bx.attrs)
          val miscChanges = changesBetweenMisc(
              id, attrChanges, ax.misc, bx.misc)
          val newCtx = ChangesLoopCtx(curr, i + 1, astl, bstl, ctx)
          changesLoop(
              curr = id,
              i = 0,
              acc = miscChanges,
              as = ax.children,
              bs = bx.children,
              ctx = Some(newCtx)
          )
        case (_ :: astl, _ :: bstl) =>
          changesLoop(id, 0, acc, astl, bstl, ctx)
      }
    }

    changesLoop(Id(0), 0, Nil, List(a), List(b), None).sorted
  }
}