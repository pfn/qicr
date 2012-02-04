package com.hanhuy.android.irc

import model.BusEvent
import AndroidConversions._

import android.os.{Handler, Looper}
import android.util.Log
import scala.collection.mutable.{ArrayBuffer,WeakHashMap,SynchronizedBuffer}


object EventBus {
    class Owner {
        val handlers = new ArrayBuffer[EventBus.Handler]
    }
    trait RefOwner {
        implicit val __eventBusRefOwner__ = new Owner
    }

    // this is terribad -- only EventBus.Remove has any meaning
    type Handler = PartialFunction[BusEvent,Any]
    object Remove // result object for Handler, if present, remove after exec
    private val TAG = "EventBus"
}
abstract class EventBus(ui: Boolean = false) {
    import EventBus.TAG
    import ref.WeakReference
    //private val queue = new ArrayBuffer[WeakReference[EventBus.Handler]]
    private val queue = new ArrayBuffer[EventBus.Handler]
            with SynchronizedBuffer[EventBus.Handler]

    private lazy val handler =
            if (ui) new Handler(Looper.getMainLooper) else null
    /*
    private def broadcast(e: BusEvent) = queue.foreach { r =>
        r.get map { h =>
            if (h.isDefinedAt(e)) if (h(e) == EventBus.Remove) this -= r
        } getOrElse { this -= r }
    }
    */
    // make immutable prior to dispatch
    private def broadcast(e: BusEvent) = (Seq.empty ++ queue) foreach { h =>
        if (h.isDefinedAt(e)) if (h(e) == EventBus.Remove) this -= h
    }

    def clear() = queue.clear
    def send(e: BusEvent) =
            if (!ui || isMainThread) broadcast(e) else post { broadcast(e) }
    def post(f: => Unit) = handler.post(f _)
    def run(f: => Unit) = if (isMainThread) f else post(f)

    // users of += must have trait EventBus.RefOwner
    def +=(handler: EventBus.Handler)(implicit owner: EventBus.Owner) {
        // long-lived objects that use EventBus must purge their owner list
        // ugh causing a memory leak--think *hard* on this
        // keep the handler only for as long as the weak reference is valid
        //owner.handlers += handler
        //queue += new WeakReference(handler)
        queue += handler
    }
    def size = queue.size
    //private def -=(e: WeakReference[EventBus.Handler]) = queue -= e
    private def -=(e: EventBus.Handler) = queue -= e
}
object UiBus extends EventBus(true)
object ServiceBus extends EventBus
