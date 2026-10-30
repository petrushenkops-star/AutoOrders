package com.example.maxsignalcopy

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only Telegram TDLib integration for the user's *own* account.
 * Never sends an order or a Telegram message. TDLib state is kept in app-private storage.
 * No automatic trading is started by this class, even when a code is found.
 */
class TelegramBridge(private val context:Context, private val onStatus:(String)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private var client:Client?=null
    @Volatile private var ready=false
    private fun info(value:String)=main.post { onStatus(value) }
    fun isReady()=ready

    private fun put(target:Any,name:String,value:Any):Boolean=try {
        val f=target.javaClass.getField(name)
        val v = when {
            f.type == java.lang.Long.TYPE && value is Number -> value.toLong()
            f.type == java.lang.Integer.TYPE && value is Number -> value.toInt()
            f.type == ByteArray::class.java && value is String -> value.toByteArray(Charsets.UTF_8)
            f.type == String::class.java && value is ByteArray -> String(value,Charsets.UTF_8)
            else -> value
        }
        f.set(target,v);true
    }catch(_:Exception){false}
    private fun get(target:Any?, name:String):Any?=try { target?.javaClass?.getField(name)?.get(target) } catch(_:Exception){null}
    private fun getAny(target:Any?,vararg names:String):Any?=names.firstNotNullOfOrNull { get(target,it) }
    private fun tdRequest(name:String, fields:Map<String,Any>):TdApi.Function<*>?=try {
        val cls=Class.forName("org.drinkless.tdlib.TdApi\$$name")
        val req=cls.getDeclaredConstructor().newInstance()
        fields.forEach { (key,v)->if(!put(req,key,v))throw IllegalStateException("Missing TDLib field: $key") }
        req as TdApi.Function<*>
    }catch(_:Exception){null}
    private fun send(name:String, fields:Map<String,Any>, cb:(TdApi.Object)->Unit={}) {
        val req=tdRequest(name,fields) ?: run {info("TDLib не поддерживает операцию $name");return}
        val c=client?:run{info("Telegram не подключён");return}
        c.send(req,{ result ->
            if (result is TdApi.Error && !(name == "LoadChats" && result.code == 404)) {
                // Never show authentication code, API hash or message text.
                info("Telegram API: ошибка ${result.code}. Проверьте соединение и права доступа.")
            }
            cb(result)
        },{ error->info("Ошибка подключения Telegram (${error.javaClass.simpleName})") })
    }
    fun connect() {
        if(client!=null) {info("Telegram уже инициализирован");return}
        if(!TelegramCredentials.configured(context)) {info("Сначала укажите api_id и api_hash");return}
        try {
            System.loadLibrary("tdjni")
            client=Client.create({ result ->
                if(result is TdApi.UpdateAuthorizationState) onAuthorization(result.authorizationState)
            },{e->info("Telegram: ${e.javaClass.simpleName}")},{e->info("Ошибка TDLib: ${e.javaClass.simpleName}")})
            info("Telegram: инициализация…")
        }catch(e:Throwable){client=null;info("TDLib не запущен (${e.javaClass.simpleName})")}
    }
    private fun onAuthorization(state:TdApi.AuthorizationState) {
        when(state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val root=context.filesDir
                val settings=mapOf<String,Any>(
                    "useTestDc" to false,
                    "databaseDirectory" to java.io.File(root,"telegram-db").absolutePath,
                    "filesDirectory" to java.io.File(root,"telegram-files").absolutePath,
                    "databaseEncryptionKey" to byteArrayOf(),
                    "useFileDatabase" to true, "useChatInfoDatabase" to true,
                    "useMessageDatabase" to true, "useSecretChats" to false,
                    "apiId" to TelegramCredentials.apiId(context),
                    "apiHash" to TelegramCredentials.apiHash(context),
                    "systemLanguageCode" to "ru", "deviceModel" to Build.MODEL,
                    "systemVersion" to Build.VERSION.RELEASE,
                    "applicationVersion" to "0.5.4"
                )
                send("SetTdlibParameters",settings)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> info("Telegram: введите номер телефона в международном формате")
            is TdApi.AuthorizationStateWaitCode -> info("Telegram: введите код авторизации в приложении, НЕ код торгового сигнала")
            is TdApi.AuthorizationStateWaitPassword -> info("Telegram: необходим пароль двухэтапной аутентификации")
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> info("Подтвердите вход в Telegram на другом устройстве")
            is TdApi.AuthorizationStateReady -> {ready=true; info("Telegram подключён. Теперь можно проверить тему «Сигнал».")}
            is TdApi.AuthorizationStateClosed -> {ready=false;client=null;info("Telegram: соединение закрыто")}
            else -> info("Telegram: этап авторизации ${state.javaClass.simpleName}")
        }
    }
    fun submitPhone(phone:String) {
        if(phone.isBlank()) {info("Введите номер телефона");return}
        send("SetAuthenticationPhoneNumber",mapOf("phoneNumber" to phone.trim()))
    }
    fun submitCode(code:String) {
        if(code.isBlank())return
        send("CheckAuthenticationCode",mapOf("code" to code.trim()))
    }
    fun submitPassword(password:String) {
        if(password.isBlank())return
        send("CheckAuthenticationPassword",mapOf("password" to password))
    }

    fun testRead(date:LocalDate, start:String, end:String, digits:Int) {
        if(!ready){info("Сначала выполните вход в Telegram");return}
        if(SignalPolicy.parseTime(start)==null || SignalPolicy.parseTime(end)==null || digits !in 4..10){
            info("Укажите время HH:mm и длину кода 4–10");return
        }
        info("Telegram: обновляю список чатов…")
        // ChatListMain is a TDLib data object, NOT a TdApi.Function.
        // Passing it through tdRequest previously made every search fail before it started.
        send("LoadChats", mapOf("chatList" to TdApi.ChatListMain(), "limit" to 100)) { result ->
            // 404 means all chats have already been loaded: still search the cached list.
            if (result is TdApi.Error && result.code != 404) {
                info("Telegram: не удалось загрузить чаты (код ${result.code}).")
                return@send
            }
            searchChat(date,start,end,digits)
        }
    }
    private fun searchChat(date:LocalDate,start:String,end:String,digits:Int) {
        fun validateChats(result: TdApi.Object) {
            if (result is TdApi.Error) return
            val ids=get(result,"chatIds") as? LongArray
            if (ids==null || ids.isEmpty()) {
                info("Чат «КОМАНДА» не найден в доступных чатах.")
                return
            }
            val matching=mutableListOf<Long>()
            fun next(i:Int) {
                if (i>=ids.size) {
                    if (matching.size!=1)
                        info("Чатов с точным именем «КОМАНДА»: ${matching.size}. Требуется выбрать однозначный чат.")
                    else searchTopic(matching.single(),date,start,end,digits)
                    return
                }
                send("GetChat", mapOf("chatId" to ids[i])) { chat ->
                    if (get(chat,"title")?.toString()?.trim()==TelegramCodePolicy.CHAT_TITLE) matching.add(ids[i])
                    next(i+1)
                }
            }
            next(0)
        }
        send("SearchChats", mapOf("query" to TelegramCodePolicy.CHAT_TITLE,"limit" to 100)) { result ->
            if (result is TdApi.Error) return@send
            val ids=get(result,"chatIds") as? LongArray
            if (ids == null || ids.isEmpty()) {
                // This server request only searches chats already known to the account.
                send("SearchChatsOnServer",
                    mapOf("query" to TelegramCodePolicy.CHAT_TITLE,"limit" to 100)) { serverResult ->
                    validateChats(serverResult)
                }
            } else validateChats(result)
        }
    }
    private fun searchTopic(chatId:Long,date:LocalDate,start:String,end:String,digits:Int) {
        info("Telegram: ищу тему «Сигнал»…")
        send("GetForumTopics",mapOf("chatId" to chatId,"query" to TelegramCodePolicy.TOPIC_TITLE,
            "offsetDate" to 0,"offsetMessageId" to 0L,"offsetForumTopicId" to 0,"limit" to 100)) { response ->
            if(response is TdApi.Error) return@send
            val topics=(get(response,"topics") as? Array<*>)?.toList().orEmpty()
            val matches=topics.mapNotNull { topic ->
                val info=getAny(topic,"info","topicInfo")?:topic
                val name=getAny(info,"name","title")?.toString()?.trim()
                val id=(getAny(info,"messageThreadId","forumTopicId") as? Number)?.toInt()
                if(name==TelegramCodePolicy.TOPIC_TITLE && id!=null)id else null
            }.distinct()
            if(matches.size!=1) {info("Тема «Сигнал» не определена однозначно (${matches.size}). Заказ не отправлялся.");return@send}
            readTopic(chatId,matches.single(),date,start,end,digits)
        }
    }
    private fun readTopic(chatId:Long,topicId:Int,date:LocalDate,start:String,end:String,digits:Int) {
        val collected=mutableListOf<TelegramSignalMessage>()
        val lower=TelegramCodePolicy.startOfWindow(date,start,end,ZoneId.systemDefault())
            ?:run{info("Неверное время");return}
        fun page(from:Long,pages:Int) {
            if(pages>=TelegramCodePolicy.MAX_PAGES){info("Telegram: сообщений слишком много, проверьте меньший период");return}
            send("GetForumTopicHistory",mapOf("chatId" to chatId,"forumTopicId" to topicId,
                "fromMessageId" to from,"offset" to 0,"limit" to TelegramCodePolicy.PAGE_SIZE)) { resp ->
                if(resp is TdApi.Error)return@send
                val arr=(get(resp,"messages") as? Array<*>)?.toList().orEmpty()
                if(arr.isEmpty()) {finish(collected,date,start,end,digits);return@send}
                var oldest=Long.MAX_VALUE
                var last=from
                for(m in arr){
                    val dateSec=(get(m,"date") as? Number)?.toLong()?:continue
                    val id=(get(m,"id") as? Number)?.toLong()?:continue
                    last=id
                    oldest=minOf(oldest,dateSec*1000L)
                    val content=get(m,"content")
                    if(content?.javaClass?.simpleName!="MessageText") continue
                    val formatted=get(content,"text")
                    val value=get(formatted,"text") as? String ?:continue
                    collected.add(TelegramSignalMessage(id,dateSec*1000L,value))
                }
                if(oldest<=lower || arr.size<TelegramCodePolicy.PAGE_SIZE || last==from)
                    finish(collected,date,start,end,digits)
                else page(last,pages+1)
            }
        }
        page(0L,0)
    }
    private fun finish(messages:List<TelegramSignalMessage>,date:LocalDate,start:String,end:String,digits:Int){
        val result=TelegramCodePolicy.inspect(messages,date,start,end,digits,System.currentTimeMillis(),ZoneId.systemDefault())
        when(result){
            is TelegramSelection.Found -> info("Telegram: код найден в теме «Сигнал» (…${result.code.takeLast(2)}), проверка без заказа завершена")
            is TelegramSelection.Rejected -> info("Telegram: ${result.reason}. Заказ не отправлялся.")
        }
    }
    fun stop() {
        ready=false
        try { client?.send(TdApi.Close(),null,null) }catch(_:Throwable){}
        client=null
    }
}
