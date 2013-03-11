package svm

import collection.mutable
import imm._
import imm.Attached.LineNumber
import annotation.tailrec
import java.security.AccessController
import virt.{Obj}
import svm.virt
import virt.Type


trait Cache[In, Out] extends (In => Out){
  val cache = mutable.Map.empty[Any, Out]
  def pre(x: In): Any = x
  def calc(x: In): Out
  def post(y: Out): Unit = ()
  def apply(x: In) = {
    val newX = pre(x)
    cache.get(newX) match{
      case Some(y) => y
      case None =>
        val newY = calc(x)
        cache(newX) = newY
        post(newY)
        newY
    }
  }
}
class VM(val natives: Natives = Natives.default, val log: ((String) => Unit)) {

  private[this] implicit val vm = this
  implicit object InternedStrings extends Cache[virt.Obj, virt.Obj]{
    override def pre(x: virt.Obj) = Virtualizer.fromVirtual[String](x)
    def calc(x: virt.Obj) = x
  }
  implicit object Types extends Cache[imm.Type, virt.Type]{
    def calc(t: imm.Type) = t match{
      case t: imm.Type.Cls => new virt.Cls(t)
      case _ => new virt.Type(t)
    }
  }

  implicit object Classes extends Cache[imm.Type.Cls, svm.Cls]{
    def calc(t: imm.Type.Cls): svm.Cls = {
      new svm.Cls(imm.Cls.parse(natives.fileLoader(t.name.replace(".", "/") + ".class").get))
    }
    override def post(cls: svm.Cls) = {
      cls.method("<clinit>", imm.Type.Desc.read("()V")).foreach( m =>
        threads(0).invoke(imm.Type.Cls(cls.name), "<clinit>", imm.Type.Desc.read("()V"), Nil)
      )
    }
  }

  lazy val threads = List(new VmThread())

  def invoke(bootClass: String, mainMethod: String, args: Seq[Any]) = {
    try{
      Virtualizer.fromVirtual[Any](
        threads(0).invoke(
          imm.Type.Cls(bootClass),
          mainMethod,
          imm.Type.Cls(bootClass).cls
            .clsData
            .methods
            .find(x => x.name == mainMethod)
            .map(_.desc)
            .getOrElse(throw new IllegalArgumentException("Can't find method: " + mainMethod)),
          args.map(Virtualizer.toVirtual[Any])
        )
      )
    }catch {case x =>

      //threads(0).dumpStack.foreach(println)
      throw x
    }
  }
  //invoke("java/lang/System", "initializeSystemClass", Nil)

}

object VmThread{
  def apply()(implicit vmt: VmThread) = vmt
}

class VmThread(val threadStack: mutable.Stack[Frame] = mutable.Stack())(implicit val vm: VM){
  import vm._
  lazy val obj = virt.Obj("java/lang/Thread",
    "name" -> "MyThread".toCharArray,
    "group" -> virt.Obj("java/lang/ThreadGroup"),
    "priority" -> 5
  )

  def frame = threadStack.head

  
  def getStackTrace =
    threadStack.map { f =>
      new StackTraceElement(
        f.runningClass.name,
        if (f.method.code != Code()) f.method.name + f.method.desc.unparse + " " + f.method.code.insns(f.pc) else "",
        f.runningClass.clsData.misc.sourceFile.getOrElse("[no source]"),
        f.method.code.attachments.flatten.reverse.collectFirst{
          case LineNumber(line, startPc) if startPc < f.pc => line
        }.getOrElse(-1)
      )
    }.toList

  def indent = "\t" * threadStack.filter(_.method.name != "Dummy").length

  def step() = {


    val topFrame = threadStack.head

    val node = topFrame.method.code.insns(topFrame.pc)
    vm.log(indent + topFrame.runningClass.name + "/" + topFrame.method.name + ": " + topFrame.stack)
    vm.log(indent + "---------------------- " + topFrame.pc + "\t" + node )
    topFrame.pc += 1
    node.op(this)


    //log(indent + topFrame.runningClass.name + "/" + topFrame.method.name + ": " + topFrame.stack.map(x => if (x == null) null else x.getClass))
  }
  def returnVal(x: Option[Any]) = {
//    log(indent + "Returning from " + threadStack.head.runningClass.name + " " + threadStack.head.method.name)
    threadStack.pop()
    x.foreach(value => threadStack.head.stack.push(value))
  }
  def dumpStack =
    threadStack.map(f =>
      f.runningClass.name.padTo(35, ' ') + (f.method.name + f.method.desc.unparse).padTo(35, ' ') + f.pc.toString.padTo(5, ' ') + (try f.method.code.insns(f.pc-1) catch {case x =>})
    )

  @tailrec final def throwException(ex: virt.Obj, print: Boolean = true): Unit = {

    threadStack.headOption match{
      case Some(frame)=>
        val handler =
          frame.method.misc.tryCatchBlocks
            .filter(x => x.start <= frame.pc && x.end >= frame.pc)
            .filter(x => !x.blockType.isDefined || ex.cls.checkIsInstanceOf(x.blockType.get))
            .headOption

        handler match{
          case None =>
            threadStack.pop()
            throwException(ex, false)
          case Some(TryCatchBlock(start, end, handler, blockType)) =>
            frame.pc = handler
            frame.stack.push(ex)
        }
      case None =>
        /*ex.apply(imm.Type.Cls("java.lang.Throwable"), "stackTrace")
          .asInstanceOf[Array[virt.Obj]]
          .map(x => "" +
            Virtualizer.fromVirtual(x(imm.Type.Cls("java.lang.StackTraceElement"), "declaringClass")) + " " +
          Virtualizer.fromVirtual(x(imm.Type.Cls("java.lang.StackTraceElement"), "methodName")))
          .foreach(println)*/

        throw new Exception("Uncaught Exception: " + Virtualizer.fromVirtual(ex(imm.Type.Cls("java.lang.Throwable"), "detailMessage")))
    }
  }
  @tailrec final def prepInvoke(tpe: imm.Type.Entity, methodName: String, desc: imm.Type.Desc, args: Seq[Any]): Unit = {

    vm.log("prepInvoke " + tpe + " " + methodName + desc.unparse)
    (vm.natives.trapped.get(tpe.name + "/" + methodName, desc), tpe) match{
      case (Some(trap), _) =>
        val result = trap(this)(args)
        if (result != ()) threadStack.head.stack.push(result)

      case (None, tpe: imm.Type.Cls) =>

        tpe.cls.clsData.methods.find(x => x.name == methodName && x.desc == desc) match {
          case Some(m) if m.code.insns != Nil=>
            //m.code.insns.zipWithIndex
            //  .foreach(x => VM.log(indent + x._2 + "\t" + x._1))

            val stretchedArgs = args.flatMap {
              case l: Long => Seq(l, l)
              case d: Double => Seq(d, d)
              case x => Seq(x)
            }
            val array = new Array[Any](m.misc.maxLocals)
            stretchedArgs.copyToArray(array)

            vm.log(indent + "args " + stretchedArgs)
            val startFrame = new Frame(
              runningClass = tpe.cls,
              method = m,
              locals = array
            )

            //log(indent + "locals " + startFrame.locals)
            threadStack.push(startFrame)
          case _ =>
            tpe.parent match{
              case Some(x) => prepInvoke(x, methodName, desc, args)
              case None => throw new Exception("Can't find method " + tpe + " " + methodName + " " + desc.unparse)
            }
        }
      case _ =>
        tpe.parent match{
          case Some(x) => prepInvoke(x, methodName, desc, args)
          case None => throw new Exception("Can't find method " + tpe + " " + methodName + " " + desc.unparse)
        }
    }


  }
  def invoke(cls: imm.Type.Cls, methodName: String, desc: imm.Type.Desc, args: Seq[Any]) = {
    val dummyFrame = new Frame(
      runningClass = cls,
      method = Method(0, "Dummy", imm.Type.Desc.read("()V")),
      locals = mutable.Seq.empty    
    )

    threadStack.push(dummyFrame)
    prepInvoke(cls, methodName, desc, args)

    while(threadStack.head != dummyFrame) step()

    threadStack.pop().stack.headOption.getOrElse(())
  }
}


class Frame(
  var pc: Int = 0,
  val runningClass: svm.Cls,
  val method: Method,
  val locals: mutable.Seq[Any] = mutable.Seq.empty,
  var stack: mutable.Stack[Any] = mutable.Stack.empty[Any])


