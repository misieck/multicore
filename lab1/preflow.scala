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
case class Push(excess: Int, height:Int, edge:Edge)
case class ChangeExcess(n: Int)
case class ChangeExcessAsk(n: Int)
case class PushAck(n:Int)
case class PushNack(excess:Int, h:Int,  source:Int )
case class Zero(n:Int)
case class Update(n:Int)

case object Print
case object PrintAsk
case object Start
case object Excess
case object Maxflow
case object SourceFlow
case object Sink
case object Hello
case object Initialise
case object Ack

class Edge(var u: ActorRef, var v: ActorRef, var c: Int) {
	var	f = 0
	def print = {
		println ("Edge flow: " + f + ", from " + u + " to " + v);
	}
}

class Node(val index: Int) extends Actor {
	implicit val t = Timeout(4 second);
	var	excess = 0;				/* excess preflow. 						*/
	var	h = 0;				/* height. 							*/
	var nacks:Int = 0
	var	control:ActorRef = null		/* controller to report to when e is zero. 			*/
	var	source:Boolean	= false		/* true if we are the source.					*/
	var	sink:Boolean	= false		/* true if we are the sink.					*/
	var	edges: List[Edge] = Nil		/* adjacency list with edge objects shared with other nodes.	*/
	var remainingEdges: List[Edge] = Nil
	var	debug = false			/* to enable printing.						*/

	def min(a:Int, b:Int) : Int = { if (a < b) a else b }

	def id: String = "@" + index;

	def other(a:Edge, u:ActorRef) : ActorRef = { if (u == a.u) a.v else a.u }

	def status: Unit = { if (debug) println(id + " e = " + excess + ", h = " + h) }

	def enter(func: String): Unit = { if (debug) { println(" ** " + id + " enters " + func +" **| " + id + " e = " + excess + ", h = " + h);} }
	def exit(func: String): Unit = { if (debug) { println(id + " exits " + func+" --| " +id + " e = " + excess + ", h = " + h); } }

	def relabel : Unit = {

		enter("relabel")

		h += 1
		nacks = 0;
		remainingEdges = edges
		discharge
		exit("relabel")
	}


	def discharge : Unit = {
		enter("  discharge, remaining:" + remainingEdges.size)
		//var	tail: List[Edge] = Nil
		var edge:Edge = null
		//tail = remainingEdges
		while (remainingEdges != Nil && excess > 0) {
			edge = remainingEdges.head
			remainingEdges = remainingEdges.tail
			var other_node = other(edge, self)
			var delta = 0;
			if (edge.u == self) { //if we are the first node in the edge then its a forward edge
				delta = min(excess, edge.c - edge.f)
				//edge.f += delta
			}
			else {
				delta = min(excess, edge.f + edge.c)
				//edge.f -= delta
			}
			if (delta != 0) {
				excess -= delta;
				other_node ! Push(delta, h, edge)
			}
		}

		if (excess > 0 && nacks > 0){
			//this.relabel
		}

		if (excess == 0){
			//this.control ! Zero(index)
		}

		exit("discharge")
	}

	def receive = {

		case Debug(debug: Boolean) => this.debug = debug

		case Print => {
			//status
		}
		case PrintAsk => {
			//status
			sender ! Ack
		}

	/*	case ChangeExcess(e:Int) => {
			excess 	+= e;
		}

		case ChangeExcessAsk(e: Int) => {
			excess += e;
			sender ! Ack
		}*/

		case Excess => {
			//enter("Excess")
			sender ! Flow(excess, index) /* send our current excess preflow to actor that asked for it. */
			//exit("Excess")
		}

		case addEdge: Edge => {
			this.edges = addEdge :: this.edges /* put this edge first in the adjacency-list. */
			this.remainingEdges = addEdge :: this.remainingEdges
		}

		case Control(control: ActorRef) => this.control = control

		case Sink => {
			sink = true
		}

		case Push(delta: Int, h: Int, edge: Edge) => {
			enter("Push, delta: " + delta + ", from h: " + h)
			if (this.h < h) {
				excess += delta;
				if (self == edge.v) { //receiving push in positive direction
					edge.f += delta;
				} else{
					edge.f -= delta;
				}
				if (! (this.sink|| this.source)) {
					this.discharge
				}
        else{
          control ! Flow( index = this.index, f = this.excess)
        }
			}
			else{
				sender ! PushNack (delta, h, this.index)
			}

			exit("Push")
		}


		case PushNack(excess:Int, height:Int, source:Int) => {
			enter("Nack( e:" + excess + ", h: " + height+ " ) from " + source)
			this.excess+=excess;
			nacks += 1;

			if (this.remainingEdges != Nil) {
				this.discharge
			}

			if (this.remainingEdges == Nil && height == this.h){
				this.relabel
			}
			//exit("Nack")
		}

		case Source(n: Int) => {
			h = n;
			source = true
		}

		case Start => {
			println("HEJA")
		}

		case Initialise => {

			assert(source == true)
			var rest: List[Edge] = edges
			var current_edge: Edge = null
			while (rest != Nil) {
				current_edge = rest.head
				rest = rest.tail
				var capacity = current_edge.c
				var other_node = other(current_edge, self)
				//println("InitPush")
				var initPush = other_node ! Push(capacity, this.h, current_edge)
				//current_edge.f += capacity
				excess -= capacity
        control ! Flow( index = this.index, f = this.excess)
			}

		}

		case Ack => {}

		case _ => {
			println("" + index + " received an unknown message" + _)
		}
			assert(false)
	}
}


class Preflow extends Actor
{

	implicit val timeout = Timeout(4 seconds);
  var flow_source = 0;
  var flow_sink = 0;
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

		case Zero(index: Int) => {
					println("Zero: " + index)
	  }

		case Flow(f:Int, index:Int) => {
			assert(index == t || index == s)

      /* somebody (hopefully the sink) told us its current excess preflow. */
			if (index == t) {
        flow_sink = f;
      } else {
        flow_source = f;
      }
      //println("  -   Flow at " +  index + ": " + f +", source: " + flow_source + ", sink: " + flow_sink );
      if (flow_source == -flow_sink && flow_sink > 0) {

        ret ! (f.abs, t)
      }
		}

		case Maxflow => {
			ret = sender
			nodes(s) ! Source(n)

			nodes(t) ! Sink

			var init = nodes(s) ! Initialise

			nodes(t) ! Excess	/* ask sink for its excess preflow (which certainly still is zero). */
		}


		case Ack => {}
	}
}

object main extends App {
	implicit val t = Timeout(20 seconds);

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
		//nodes(i) ! Debug(true)
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

	println("f = " + f)

	system.stop(control);
	system.terminate()

	val	end = System.currentTimeMillis()

	println("t = " + (end - begin) / 1000.0 + " s")
}
