package com.fivegmag.a5gmsmediasessionhandler.service

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

class OutgoingMessageHandler() {

    private var nativeIncomingMessenger: Messenger? = null

    companion object {
        const val TAG = "5GMS-IncomingMessageHandler"
    }

    fun setNativeIncomingMessenger(messenger: Messenger) {
        nativeIncomingMessenger = messenger
    }

    fun sendMessage(messageType: Int, bundle: Bundle, messenger: Messenger?) {
        if (messenger == null) {
            Log.e(TAG, "No messenger provided. Cant send message")
            return
        }
        val msg = getMessage(messageType)
        msg.data = bundle
        sendMessage(msg, messenger)
    }

    private fun getMessage(messageType: Int): Message {
        val msg: Message = Message.obtain(
            null,
            messageType
        )
        msg.replyTo = nativeIncomingMessenger

        return msg
    }

    private fun sendMessage(msg: Message, messenger: Messenger) {
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    fun reset() {
        nativeIncomingMessenger = null
    }

}