package inkspace.exchange

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Verifies a fresh provider output by streaming it back after close, including silent truncation. */
class OutputReceipt {
    private val digest=MessageDigest.getInstance("SHA-256")
    private var count=0L
    private var expected: ByteArray?=null
    fun stream(target: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte()),0,1) }
        override fun write(bytes: ByteArray,offset: Int,length: Int) { target.write(bytes,offset,length);digest.update(bytes,offset,length);count+=length }
        override fun flush()=target.flush()
        override fun close() { target.close();expected=digest.digest() }
    }
    fun verify(input: InputStream) {
        val expected=requireNotNull(expected) {"Output has not closed"}
        val actual=MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(32768);var size=0L
        input.use { while(true) {
            val n=it.read(buffer);if(n<0)break
            if(n==0)throw ExchangeException("Provider verification made no progress")
            size+=n;if(size>count)throw ExchangeException("Provider output size mismatch")
            actual.update(buffer,0,n)
        } }
        if(size!=count || !actual.digest().contentEquals(expected))throw ExchangeException("Provider output was truncated or changed")
    }
}
