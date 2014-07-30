/**
# Fast Fourier Transform (FFT)
<a name="sec:Afft"></a>

We consider staging a fast fourier transform (FFT) algorithm.  A staged FFT,
implemented in MetaOCaml, has been presented by Kiselyov et~al.\
[(*)](DBLP:conf/emsoft/KiselyovST04) Their work is a very good example for how
staging allows to transform a simple, unoptimized algorithm into an efficient
program generator. Achieving this in the context of MetaOCaml, however,
required restructuring the program into monadic style and adding a front-end
layer for performing symbolic rewritings. Using our approach of just adding
`Rep` types, we can go from the naive textbook-algorithm to the staged version
(shown below) by changing literally two lines of code:

    trait FFT { this: Arith with Trig =>
      case class Complex(re: Rep[Double], im: Rep[Double])
      ...
    }

All that is needed is adding the self-type annotation to import arithmetic and
trigonometric operations and changing the type of the real and imaginary
components of complex numbers from `Double` to `Rep[Double]`.

    trait FFT { this: Arith with Trig =>
      case class Complex(re: Rep[Double], im: Rep[Double]) {
        def +(that: Complex) = Complex(this.re + that.re, this.im + that.im)
        def *(that: Complex) = ...
      }
      def omega(k: Int, N: Int): Complex = {
        val kth = -2.0 * k * Math.Pi / N
        Complex(cos(kth), sin(kth))
      }
      def fft(xs: Array[Complex]): Array[Complex] = xs match {
        case (x :: Nil) => xs
        case _ =>
          val N = xs.length // assume it's a power of two
          val (even0, odd0) = splitEvenOdd(xs)
          val (even1, odd1) = (fft(even0), fft(odd0))
          val (even2, odd2) = (even1 zip odd1 zipWithIndex) map {
            case ((x, y), k) =>
              val z = omega(k, N) * y
              (x + z, x - z)
          }.unzip;
          even2 ::: odd2
      }
    }

FFT code. Only the real and imaginary components of complex numbers need to be
staged.

\begin{figure}\centering
\includegraphics[scale=0.5]{papers/cacm2012/figures/test2-fft2-x-dot.pdf}
\caption{\label{fig:fftgraph} Computation graph for size-4 FFT. Auto-generated from
staged code in Figure~\ref{fig:fftcode}.}
\end{figure}



Merely changing the types  will not provide us with  the desired optimizations
yet.  We will see below how we can add the transformations described by
Kiselyov et~al.\ to generate the same fixed-size FFT code, corresponding to
the famous FFT butterfly networks (see Figure~\ref{fig:fftgraph}). Despite the
seemingly naive algorithm, this staged code is free of branches, intermediate
data structures and redundant computations. The important point here is that
we can add these transformations without any further changes to the code in
Figure~\ref{fig:fftcode}, just by mixing in the trait `FFT` with a few others.


    trait ArithExpOptFFT extends ArithExp {
      override def infix_*(x:Exp[Double],y:Exp[Double]) = (x,y) match {
        case (Const(k), Def(Times(Const(l), y))) => Const(k * l) * y
        case (x, Def(Times(Const(k), y))) => Const(k) * (x * y))
        case (Def(Times(Const(k), x)), y) => Const(k) * (x * y))
        ...
        case (x, Const(y)) => Times(Const(y), x)
        case _ => super.infix_*(x, y)
      }
    }

Extending the generic implementation from [here](#sec:308addOpts) with FFT-
specific optimizations.




## Implementing Optimizations

As already discussed [here](#sec:308addOpts), some profitable optimizations
are very generic (CSE, DCE, etc), whereas others are specific to the actual
program. In the FFT case, Kiselyov et al.\
[(*)](DBLP:conf/emsoft/KiselyovST04) describe  a number of rewritings that are
particularly effective for the patterns of code generated by the FFT algorithm
but not as much for other programs.

What we want to achieve again is modularity, such that optimizations can be
combined in a way that is most useful for a given task.  This can be achieved
by overriding smart constructors,  as shown by trait `ArithExpOptFFT` (see
Figure~\ref{fig:expOpt}).  Note that the use of `x*y` within the body of
`infix_*` will apply the optimization  recursively.



## Running the Generated Code

Extending the FFT component from Figure~\ref{fig:fftcode} with explicit
compilation.

    trait FFTC extends FFT { this: Arrays with Compile =>
      def fftc(size: Int) = compile { input: Rep[Array[Double]] =>
        assert(<size is power of 2>) // happens at staging time
        val arg = Array.tabulate(size) { i => 
          Complex(input(2*i), input(2*i+1))
        }
        val res = fft(arg)
        updateArray(input, res.flatMap {
          case Complex(re,im) => Array(re,im)
        })
      }
    }


Using the staged FFT implementation as part of some larger Scala program is
straightforward but requires us to interface the generic algorithm with a
concrete data representation. The algorithm in Figure~\ref{fig:fftcode}
expects an array of `Complex` objects as input, each of which contains fields
of type `Rep[Double]`. The algorithm itself has no notion of staged arrays but
uses arrays only in the generator stage, which means that it is agnostic to
how data is stored. The enclosing program, however, will store arrays of
complex numbers in some native format which we will need to feed into the
algorithm. A simple choice of representation is to use `Array[Double]` with
the complex numbers flattened into adjacent slots. When applying `compile`, we
will thus receive  input of type `Rep[Array[Double]]`.  Figure~\ref{fig:fftc}
shows how we can  extend trait `FFT` to `FFTC` to obtain compiled FFT
implementations that realize the necessary data interface for a  fixed input
size.


We can then define code that creates and uses compiled  FFT ``codelets'' by
extending `FFTC`:

    trait TestFFTC extends FFTC {
      val fft4: Array[Double] => Array[Double] = fftc(4) 
      val fft8: Array[Double] => Array[Double] = fftc(8) 

      // embedded code using fft4, fft8, ...
    }

Constructing an instance of this subtrait (mixed in with the appropriate LMS
traits) will execute the embedded code:

    val OP: TestFFC = new TestFFTC with CompileScala
      with ArithExpOpt  with ArithExpOptFFT with ScalaGenArith
      with TrigExpOpt   with ScalaGenTrig 
      with ArraysExpOpt with ScalaGenArrays

We can also use the compiled methods from outside the object:

    OP.fft4(Array(1.0,0.0, 1.0,0.0, 2.0,0.0, 2.0,0.0))
    $\hookrightarrow$ Array(6.0,0.0,-1.0,1.0,0.0,0.0,-1.0,-1.0)

Providing an explicit type in the definition `val OP: TestFFC = ...` ensures
that the internal representation is not accessible from the outside, only the
members defined by `TestFFC`.


The full code is below:

    package scala.virtualization.lms
    package epfl
    package test2

    import common._
    import test1._
    import reflect.SourceContext

    import java.io.PrintWriter

    import org.scalatest._


    trait FFT { this: Arith with Trig =>
      
      def omega(k: Int, N: Int): Complex = {
        val kth = -2.0 * k * math.Pi / N
        Complex(cos(kth), sin(kth))
      }

      case class Complex(re: Rep[Double], im: Rep[Double]) {
        def +(that: Complex) = Complex(this.re + that.re, this.im + that.im)
        def -(that: Complex) = Complex(this.re - that.re, this.im - that.im)
        def *(that: Complex) = Complex(this.re * that.re - this.im * that.im, this.re * that.im + this.im * that.re)
      }

      def splitEvenOdd[T](xs: List[T]): (List[T], List[T]) = (xs: @unchecked) match {
        case e :: o :: xt =>
          val (es, os) = splitEvenOdd(xt)
          ((e :: es), (o :: os))
        case Nil => (Nil, Nil)
        // cases?
      }

      def mergeEvenOdd[T](even: List[T], odd: List[T]): List[T] = ((even, odd): @unchecked) match {
        case (Nil, Nil) =>
          Nil
        case ((e :: es), (o :: os)) =>
          e :: (o :: mergeEvenOdd(es, os))
        // cases?
      }

      def fft(xs: List[Complex]): List[Complex] = xs match {
        case (x :: Nil) => xs
        case _ =>
          val N = xs.length // assume it's a power of two
          val (even0, odd0) = splitEvenOdd(xs)
          val (even1, odd1) = (fft(even0), fft(odd0))
          val (even2, odd2) = (even1 zip odd1 zipWithIndex) map {
            case ((x, y), k) =>
              val z = omega(k, N) * y
              (x + z, x - z)
          } unzip;
          even2 ::: odd2
      }

    }





    trait ArithExpOptFFT extends ArithExpOpt {

      override def infix_+(x: Exp[Double], y: Exp[Double])(implicit pos: SourceContext) = (x, y) match {
        case (x, Def(Minus(Const(0.0) | Const(-0.0), y))) => infix_-(x, y)
        case _ => super.infix_+(x, y)
      }

      override def infix_-(x: Exp[Double], y: Exp[Double])(implicit pos: SourceContext) = (x, y) match {
        case (x, Def(Minus(Const(0.0) | Const(-0.0), y))) => infix_+(x, y)
        case _ => super.infix_-(x, y)
      }

      override def infix_*(x: Exp[Double], y: Exp[Double])(implicit pos: SourceContext) = (x, y) match {
        case (x, Const(-1.0)) => infix_-(0.0, x)
        case (Const(-1.0), y) => infix_-(0.0, y)
        case _ => super.infix_*(x, y)
      }
    }



    trait TrigExpOptFFT extends TrigExpOpt {
      override def cos(x: Exp[Double]) = x match {
        case Const(x) if { val z = x / math.Pi / 0.5; z != 0 && z == z.toInt } => Const(0.0)
        case _ => super.cos(x)
      }
    }


    trait FlatResult extends BaseExp { // just to make dot output nicer

      case class Result(x: Any) extends Def[Any]
      
      def result(x: Any): Exp[Any] = toAtom(Result(x))
      
    }

    trait ScalaGenFlat extends ScalaGenBase {
       import IR._
       type Block[+T] = Exp[T]
       def getBlockResultFull[T](x: Block[T]): Exp[T] = x
       def reifyBlock[T:Manifest](x: =>Exp[T]): Block[T] = x
       def traverseBlock[A](block: Block[A]): Unit = {
         buildScheduleForResult(block) foreach traverseStm
       }
    }



    class TestFFT extends FileDiffSuite {
      
      val prefix = home + "test-out/epfl/test2-"
      
      def testFFT1 = {
        withOutFile(prefix+"fft1") {
          val o = new FFT with ArithExp with TrigExpOpt with FlatResult with DisableCSE //with DisableDCE
          import o._

          val r = fft(List.tabulate(4)(_ => Complex(fresh, fresh)))
          println(globalDefs.mkString("\n"))
          println(r)
          
          val p = new ExportGraph with DisableDCE { val IR: o.type = o }
          p.emitDepGraph(result(r), prefix+"fft1-dot", true)
        }
        assertFileEqualsCheck(prefix+"fft1")
        assertFileEqualsCheck(prefix+"fft1-dot")
      }

      def testFFT2 = {
        withOutFile(prefix+"fft2") {
          val o = new FFT with ArithExpOptFFT with TrigExpOptFFT with FlatResult
          import o._

          case class Result(x: Any) extends Exp[Any]
          
          val r = fft(List.tabulate(4)(_ => Complex(fresh, fresh)))
          println(globalDefs.mkString("\n"))
          println(r)

          val p = new ExportGraph { val IR: o.type = o }
          p.emitDepGraph(result(r), prefix+"fft2-dot", true)
        }
        assertFileEqualsCheck(prefix+"fft2")
        assertFileEqualsCheck(prefix+"fft2-dot")
      }

      def testFFT3 = {
        withOutFile(prefix+"fft3") {
          class FooBar extends FFT
            with ArithExpOptFFT with TrigExpOptFFT with ArraysExp
            with CompileScala {

            def ffts(input: Rep[Array[Double]], size: Int) = {
              val list = List.tabulate(size)(i => Complex(input(2*i), input(2*i+1)))
              val r = fft(list)
              // make a new array for now - doing in-place update would be better
              makeArray(r.flatMap { case Complex(re,im) => List(re,im) })
            }
            
            val codegen = new ScalaGenFlat with ScalaGenArith with ScalaGenArrays { val IR: FooBar.this.type = FooBar.this } // TODO: find a better way...
          }
          val o = new FooBar
          import o._
        
          val fft4 = (input: Rep[Array[Double]]) => ffts(input, 4)
          codegen.emitSource(fft4, "FFT4", new PrintWriter(System.out))
          val fft4c = compile(fft4)
          println(fft4c(Array(1.0,0.0, 1.0,0.0, 2.0,0.0, 2.0,0.0, 1.0,0.0, 1.0,0.0, 0.0,0.0, 0.0,0.0)).mkString(","))
        }
        assertFileEqualsCheck(prefix+"fft3")
      }
      
    }
*/