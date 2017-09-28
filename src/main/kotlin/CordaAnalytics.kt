package net.corda.analytics

import org.http4k.client.ApacheClient
import org.http4k.core.Method
import org.http4k.core.Request
import kotlin.concurrent.thread

interface CordaAnalytics {
    fun nodeStart(nodeName: String = "Corda Node")
    fun nodeStop(nodeName: String = "Corda Node")
    fun nodeCrash(e: Exception? = null)
    fun registerFlow(flowName: String)
    fun flowStart(flowName: String)
    fun flowProgress(flowState: String)
    fun flowLoaded(flowName: String)
    fun flowEnd(flowName: String)
    fun exception(exception: Exception)
}

open class NullAnalytics() : CordaAnalytics {
    override fun nodeStart(nodeName: String) = Unit
    override fun nodeStop(nodeName: String) = Unit
    override fun nodeCrash(e: Exception?) = Unit
    override fun registerFlow(flowName: String) = Unit
    override fun flowStart(flowName: String) = Unit
    override fun flowProgress(flowState: String) = Unit
    override fun flowLoaded(flowName: String) = Unit
    override fun flowEnd(flowName: String) = Unit
    override fun exception(exception: Exception) = Unit
}

/*
 * https://developers.google.com/analytics/devguides/collection/protocol/v1/
 */
open class GoogleCordaAnalytics(val tracking: String, val debug: Boolean = false, var overrides: MutableMap<String, String> = mutableMapOf("cid" to "UIDNotSet")) : CordaAnalytics {
    val GAHost = if (debug) "http://www.google-analytics.com/debug/collect" else "http://www.google-analytics.com/collect"

    enum class Event {
        NODE_START,
        NODE_STOP,
        NODE_CRASH,
        REGISTER_FLOW,
        FLOW_START,
        FLOW_PROGRESS,
        FLOW_LOADED,
        FLOW_END,
        EXCEPTION {
            override fun getType() = "exception"
        };

        open fun getType() = "event"
    }

    fun sendHit(e: GoogleCordaAnalytics.Event, data: String? = null) {

        val m = emptyMap<String, String>().toMutableMap()

        var request = Request(Method.POST, GAHost)

        overrides.map {
            request = request.query(it.key, it.value)
        }

        // Mandatory
        m["v"] = "1"
        m["t"] = e.getType()
        m["tid"] = tracking

        when (e) {
            Event.EXCEPTION ->
                m["exd"] = data!!
            Event.FLOW_LOADED, Event.FLOW_PROGRESS, Event.FLOW_START, Event.FLOW_END -> {
                m["ec"] = e.toString()
                m["ea"] = data!!
                m["el"] = data!!
                m["ev"] = "1"
            }
            Event.NODE_START -> {
                m["ec"] = e.toString()
                m["ea"] = data!!
                m["el"] = data!!
                m["ev"] = "1"
            }
            else -> {
                if (data != null) {
                    m["dt"] = data
                }
            }
        }

        //Optional
        m["ds"] = "CorDapp"
        m["dh"] = "corda.net"
        m["dp"] = e.toString()

        m.map { request = request.query(it.key, it.value) }

        overrides.map {
            request.query(it.key, it.value)
        }

        val client = ApacheClient()

        thread(start = true) {
            val cr = client(request)
            if (debug) {
                println(cr)
            }
        }
    }

    override fun flowProgress(flowState: String) = sendHit(Event.FLOW_PROGRESS, flowState)
    override fun nodeStart(nodeName: String) = sendHit(Event.NODE_START, nodeName)
    override fun nodeStop(nodeName: String) = sendHit(Event.NODE_STOP, nodeName)
    override fun nodeCrash(e: Exception?) = sendHit(Event.NODE_CRASH)
    override fun flowStart(data: String) = sendHit(Event.FLOW_START, data)
    override fun flowLoaded(flowName: String) = sendHit(Event.FLOW_LOADED, flowName)
    override fun flowEnd(data: String) = sendHit(Event.FLOW_END, data)
    override fun registerFlow(data: String) = sendHit(Event.REGISTER_FLOW, data)
    override fun exception(exception: Exception) = sendHit(Event.EXCEPTION, exception.toString())
}

fun main(args: Array<String>) {
    var gca = GoogleCordaAnalytics("UA-106986514-1")

    gca.nodeStart("my node")
    Thread.sleep(2000)

    gca.flowStart("Test Flow")
    Thread.sleep(2000)

    gca.flowProgress("Step 1")
    Thread.sleep(2000)

    gca.flowProgress("Step 2")
    Thread.sleep(2000)

    gca.flowEnd("Test Flow")
    Thread.sleep(2000)

    gca.nodeStop("my node")
}


