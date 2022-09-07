import scala.util._
import java.util.Scanner
import java.io._
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.{Await,ExecutionContext,Future,Promise}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.io._

case class Flow(f: Int, index: Int)
case class Debug(debug: Boolean)
case class Control(control:ActorRef)
case class Source(n: Int)

case object Print
case object Start
case object Excess
case object Maxflow
case object SourceFlow
case object Sink
case object Hello

class Edge(var u: ActorRef, var v: ActorRef, var c: Int) {
	var	f = 0
}

class Node(val index: Int) extends Actor {
	var	excess = 0;				/* excess preflow. 						*/
	var	h = 0;				/* height. 							*/
	var	control:ActorRef = null		/* controller to report to when e is zero. 			*/
	var	source:Boolean	= false		/* true if we are the source.					*/
	var	sink:Boolean	= false		/* true if we are the sink.					*/
	var	edges: List[Edge] = Nil		/* adjacency list with edge objects shared with other nodes.	*/
	var	debug = false			/* to enable printing.						*/

	def min(a:Int, b:Int) : Int = { if (a < b) a else b }

	def id: String = "@" + index;

	def other(a:Edge, u:ActorRef) : ActorRef = { if (u == a.u) a.v else a.u }

	def status: Unit = { if (debug) println(id + " e = " + excess + ", h = " + h) }

	def enter(func: String): Unit = { if (debug) { println(id + " enters " + func); status } }
	def exit(func: String): Unit = { if (debug) { println(id + " exits " + func); status } }

	def relabel : Unit = {

		enter("relabel")

		h += 1

		exit("relabel")
	}

	def receive = {

		case Debug(debug: Boolean)	=> this.debug = debug

		case Print => status

		case Excess => { sender ! Flow(excess, index) /* send our current excess preflow to actor that asked for it. */ }

		case addEdge:Edge => { this.edges = addEdge :: this.edges /* put this edge first in the adjacency-list. */ }

		case Control(control:ActorRef)	=> this.control = control

		case Sink	=> { sink = true }

		case Source(n:Int)	=> { h = n; source = true }

		case Start => { println ("HEJA")}

		case _		=> {
			println("" + index + " received an unknown message" + _)
			assert(false)
		}
	}

}


class Preflow extends Actor
{
	var	s	= 0;			/* index of source node.					*/
	var	t	= 0;			/* index of sink node.					*/
	var	n	= 0;			/* number of vertices in the graph.				*/
	var	edges:Array[Edge]	= null	/* edges in the graph.						*/
	var	nodes:Array[ActorRef]	= null	/* vertices in the graph.					*/
	var	ret:ActorRef 		= null	/* Actor to send result to.					*/

	def receive = {

		case node:Array[ActorRef]	=> {
			this.nodes = node
			n = node.size
			s = 0
			t = n-1
			for (u <- node)
				u ! Control(self)
		}

		case edge:Array[Edge] => this.edges = edge


		case Flow(f:Int, index:Int) => {
			ret ! (f, index)			/* somebody (hopefully the sink) told us its current excess preflow. */
		}

		case Maxflow => {
			ret = sender
			nodes(s) ! Source(n)
			nodes(t) ! Sink
			nodes(s) ! Start

			nodes(t) ! Excess	/* ask sink for its excess preflow (which certainly still is zero). */
		}
		case SourceFlow => {
			ret = sender
			nodes(s) ! Excess	/* ask sink for its excess preflow (which certainly still is zero). */
		}
	}
}

object main extends App {
	implicit val t = Timeout(4 seconds);

	val	begin = System.currentTimeMillis()
	val system = ActorSystem("Main")
	val control = system.actorOf(Props[Preflow], name = "control")

	var	n = 0;
	var	m = 0;
	var	edges: Array[Edge] = null
	var	nodes: Array[ActorRef] = null

	val	s = new Scanner(System.in);

	n = s.nextInt
	m = s.nextInt

	/* next ignore c and p from 6railwayplanning */
	s.nextInt
	s.nextInt

	nodes = new Array[ActorRef](n)

	for (i <- 0 to n-1) {
		nodes(i) = system.actorOf(Props(new Node(i)), name = "v" + i)
		nodes(i) ! Debug(true)
	}

	edges = new Array[Edge](m)

	for (i <- 0 to m-1) {

		val u = s.nextInt
		val v = s.nextInt
		val c = s.nextInt

		edges(i) = new Edge(nodes(u), nodes(v), c)

		nodes(u) ! edges(i)
		nodes(v) ! edges(i)
	}

	control ! nodes
	control ! edges


	val flow = control ? Maxflow

	val (f, index) = Await.result(flow, t.duration)


	println("f = " + f + ", index = " + index)

	system.stop(control);
	system.terminate()

	val	end = System.currentTimeMillis()

	println("t = " + (end - begin) / 1000.0 + " s")
}
