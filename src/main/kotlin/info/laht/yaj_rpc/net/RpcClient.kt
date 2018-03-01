/*
 * The MIT License
 *
 * Copyright 2018 Lars Ivar Hatledal
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package info.laht.yaj_rpc.net

import info.laht.yaj_rpc.RpcNoParams
import info.laht.yaj_rpc.RpcParams
import info.laht.yaj_rpc.RpcRequestOut
import info.laht.yaj_rpc.RpcResponse

import java.util.UUID
import java.util.concurrent.CountDownLatch

import org.slf4j.Logger
import org.slf4j.LoggerFactory


typealias Consumer<T> = (T) -> Unit

interface RpcClient: AutoCloseable {

    fun notify(methodName: String, params: RpcParams = RpcParams.noParams())
    fun write(methodName: String, params: RpcParams = RpcNoParams): RpcResponse

}

interface AsyncClient: RpcClient {

    fun writeAsync(methodName: String, params: RpcParams = RpcParams.noParams(), callback: Consumer<RpcResponse>)

}

abstract class AbstractRpcClient : RpcClient  {

    override fun notify(methodName: String, params: RpcParams) {
        write(RpcRequestOut(methodName, params).let { it.toJson() })
    }

    override fun write(methodName: String, params: RpcParams): RpcResponse {

        val request = RpcRequestOut(methodName, params).apply {
            id = UUID.randomUUID().toString()
        }.let { it.toJson() }
        return RpcResponse.fromJson(write(request))

    }

    protected abstract fun write(msg: String): String

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(AbstractAsyncRpcClient::class.java)
    }
}

abstract class AbstractAsyncRpcClient : AsyncClient  {

    private val callbacks = mutableMapOf<String, Consumer<RpcResponse>>()

    override fun notify(methodName: String, params: RpcParams) {
        write(RpcRequestOut(methodName, params).let { it.toJson() })
    }

    override fun writeAsync(methodName: String, params: RpcParams, callback: Consumer<RpcResponse>) {
        val request = RpcRequestOut(methodName, params).apply {
            id = UUID.randomUUID().toString()
            callbacks[id.toString()] = callback
        }.let { it.toJson() }
        write(request)
    }


    override fun write(methodName: String, params: RpcParams): RpcResponse {

        var response: RpcResponse? = null
        val latch = CountDownLatch(1)
        val request = RpcRequestOut(methodName, params).apply {
            id = UUID.randomUUID().toString()
            callbacks[id.toString()] = {
                response = it
                latch.countDown()
            }
        }.let { it.toJson() }
        write(request)
        latch.await()
        return response!!

    }

    protected abstract fun write(msg: String)

    protected fun messageReceived(message: String) {
        val response = RpcResponse.fromJson(message)
        if (response.error != null) {
            LOG.warn("RPC invocation returned error: ${response.error}")
        } else {
            val id = response.id.toString()
            callbacks[id]?.also { callback ->
                callback.invoke(response)
            }
            callbacks.remove(id)
        }
    }

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(AbstractAsyncRpcClient::class.java)
    }

}