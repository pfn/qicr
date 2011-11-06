package com.hanhuy.android.irc.model

class Channel(_server: Server, _name: String) {
    val messages = new QueueAdapter[String]
    def name = _name
    def server = _server
}
