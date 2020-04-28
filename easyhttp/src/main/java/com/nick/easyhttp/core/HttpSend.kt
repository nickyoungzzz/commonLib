package com.nick.easyhttp.core

import com.nick.easyhttp.config.EasyHttp
import com.nick.easyhttp.config.HttpConfig
import com.nick.easyhttp.core.download.DownloadHandler
import com.nick.easyhttp.core.download.DownloadParam
import com.nick.easyhttp.core.download.DownloadState
import com.nick.easyhttp.core.req.HttpHandler
import com.nick.easyhttp.result.HttpReq
import com.nick.easyhttp.result.HttpResp
import com.nick.easyhttp.result.HttpResult
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.*

class HttpSend internal constructor(private val reqUrl: String, private val reqMethod: ReqMethod) {

	private var reqTag: Any? = null

	private var queryMap = hashMapOf<String, String>()

	private var headerMap = hashMapOf<String, String>()

	private var fieldMap = hashMapOf<String, String>()

	private val multipartBody = hashMapOf<String, Any>()

	private var isMultiPart = false

	private var jsonString = ""

	private var asDownload = false

	private var httpConfig: HttpConfig = EasyHttp.httpConfig

	private var httpHandler: HttpHandler = httpConfig.httpHandler

	private var downloadHandler: DownloadHandler = httpConfig.downLoadHandler

	private lateinit var downloadParam: DownloadParam

	fun addQuery(key: String, value: String) = apply {
		queryMap[key] = value
	}

	fun addQueries(queryMap: HashMap<String, String>) = apply {
		if (queryMap.isNotEmpty()) {
			this.queryMap.forEach {
				if (!this.queryMap.containsKey(it.key)) {
					this.queryMap[it.key] = it.value
				}
			}
		}
	}

	fun addField(key: String, value: String) = apply {
		isMultiPart = false
		fieldMap[key] = value
	}

	fun addFields(fieldMap: HashMap<String, String>) = apply {
		if (fieldMap.isNotEmpty()) {
			fieldMap.forEach {
				if (!this.fieldMap.containsKey(it.key)) {
					addField(it.key, it.value)
				}
			}
		}
	}

	fun addJsonString(jsonString: String) = apply {
		this.jsonString = jsonString
	}

	fun addMultiPart(key: String, value: Any) = apply {
		isMultiPart = true
		multipartBody[key] = value
	}

	fun addHeader(key: String, value: String) = apply {
		headerMap[key] = value
	}

	fun addHeaders(headerMap: HashMap<String, String>) = apply {
		if (headerMap.isNotEmpty()) {
			headerMap.forEach {
				if (!this.headerMap.containsKey(it.key)) {
					addHeader(it.key, it.value)
				}
			}
		}
	}

	fun tag(reqTag: Any) = apply {
		this.reqTag = reqTag
	}

	fun isMultiPart() = apply {
		this.isMultiPart = true
	}

	@JvmOverloads
	fun asDownload(downloadParam: DownloadParam = DownloadParam()) = apply {
		this.asDownload = true
		this.downloadParam = downloadParam
	}

	fun setHttpHandler(httpHandler: HttpHandler) = apply {
		this.httpHandler = httpHandler
	}

	private var beforeExecute = fun(httpReq: HttpReq): HttpReq = httpReq

	private var afterExecute = fun(_: HttpReq, httpResp: HttpResp) = httpResp

	fun beforeSend(before: (httpReq: HttpReq) -> HttpReq) = apply { this.beforeExecute = before }

	fun afterReply(after: (httpReq: HttpReq, httpResp: HttpResp) -> HttpResp) = apply { this.afterExecute = after }

	private fun getProxyHttpHandler(): HttpHandler {
		return Proxy.newProxyInstance(httpHandler.javaClass.classLoader, httpHandler.javaClass.interfaces,
			HttpInvocation(httpHandler, beforeExecute, afterExecute, httpReq())) as HttpHandler
	}

	fun setDownloadHandler(downloadHandler: DownloadHandler) = apply {
		this.downloadHandler = downloadHandler
	}

	private fun httpReq(): HttpReq {
		return HttpReq.Builder().url(reqUrl).reqMethod(reqMethod).reqTag(reqTag).queryMap(queryMap).fieldMap(fieldMap)
			.headerMap(headerMap).multipartBody(multipartBody).isMultiPart(isMultiPart).jsonString(jsonString)
			.asDownload(asDownload)
			.build()
	}

	fun send(): HttpResult {
		val httpReq = httpConfig.before(httpReq())
		val httpResp = httpConfig.after(httpReq, getProxyHttpHandler().execute(httpReq))
		val status = if (httpResp.exception != null) HttpStatus.EXCEPTION
		else (if (httpResp.isSuccessful) HttpStatus.SUCCESS else HttpStatus.ERROR)
		return HttpResult.Builder().code(httpResp.code)
			.headers(httpResp.headers)
			.resp(httpResp.resp)
			.throwable(httpResp.exception)
			.status(status)
			.build()
	}

	@JvmOverloads
	fun download(exc: (e: Throwable) -> Unit = {}, download: (downloadState: DownloadState) -> Unit = {}) = apply {
		val source = downloadParam.source
		val range = if (downloadParam.breakPoint && source.exists()) source.length() else 0
		val httpResp = getProxyHttpHandler().execute(httpReq().apply { headerMap["Range"] = "bytes=${range}-" })
		if (httpResp.isSuccessful) {
			try {
				downloadHandler.saveFile(httpResp.inputStream!!, downloadParam, httpResp.contentLength) { state ->
					download(state)
				}
			} catch (e: IOException) {
				exc(e)
			}
		} else {
			exc(httpResp.exception!!)
		}
		return this
	}

	fun cancelRequest() {
		httpHandler.cancel()
		if (asDownload) {
			downloadHandler.cancel()
		}
	}

	internal class HttpInvocation internal constructor(private val any: Any, private val before: (httpReq: HttpReq) -> HttpReq,
	                                                   private val after: (httpReq: HttpReq, httpResp: HttpResp) -> HttpResp,
	                                                   private val httpReq: HttpReq
	) : InvocationHandler {
		override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any {
			return if (method?.name == "execute") {
				val req = before(httpReq)
				val obj = method.invoke(any, req)
				after(req, obj as HttpResp)
			} else {
				method!!.invoke(any, args)
			}
		}
	}
}