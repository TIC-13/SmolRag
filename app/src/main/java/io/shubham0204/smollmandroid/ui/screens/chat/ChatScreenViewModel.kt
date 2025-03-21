/*
 * Copyright (C) 2024 Shubham Panchal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shubham0204.smollmandroid.ui.screens.chat

import android.content.Context
import android.graphics.Color
import android.text.util.Linkify
import android.util.Log
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModel
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import io.shubham0204.smollm.SmolLM
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.data.ChatMessage
import io.shubham0204.smollmandroid.data.ChatsDB
import io.shubham0204.smollmandroid.data.MessagesDB
import io.shubham0204.smollmandroid.data.TasksDB
import io.shubham0204.smollmandroid.llm.ModelsRepository
import io.shubham0204.smollmandroid.prism4j.PrismGrammarLocator
import io.shubham0204.smollmandroid.ui.components.createAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel
import java.util.Date
import kotlin.time.measureTime
import ai.luxai.rag.Rag
import ai.luxai.rag.FileUtilities
import ai.luxai.rag.RerankingProps

const val LOGTAG = "[SmolLMAndroid-Kt]"
val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }

@KoinViewModel
class ChatScreenViewModel(
    val context: Context,
    val messagesDB: MessagesDB,
    val chatsDB: ChatsDB,
    val modelsRepository: ModelsRepository,
    val tasksDB: TasksDB,
) : ViewModel() {
    enum class ModelLoadingState {
        NOT_LOADED, // model loading not started
        IN_PROGRESS, // model loading in-progress
        SUCCESS, // model loading finished successfully
        FAILURE, // model loading failed
    }

    // UI state variables
    private val _currChatState = MutableStateFlow<Chat?>(null)
    val currChatState: StateFlow<Chat?> = _currChatState

    private val _isGeneratingResponse = MutableStateFlow(false)
    val isGeneratingResponse: StateFlow<Boolean> = _isGeneratingResponse

    private val _modelLoadState = MutableStateFlow(ModelLoadingState.NOT_LOADED)
    val modelLoadState: StateFlow<ModelLoadingState> = _modelLoadState

    private val _partialResponse = MutableStateFlow("")
    val partialResponse: StateFlow<String> = _partialResponse

    private val _showSelectModelListDialogState = MutableStateFlow(false)
    val showSelectModelListDialogState: StateFlow<Boolean> = _showSelectModelListDialogState

    private val _showMoreOptionsPopupState = MutableStateFlow(false)
    val showMoreOptionsPopupState: StateFlow<Boolean> = _showMoreOptionsPopupState

    private val _showTaskListBottomListState = MutableStateFlow(false)
    val showTaskListBottomListState: StateFlow<Boolean> = _showTaskListBottomListState

    private var responseGenerationJob: Job? = null
    private val smolLM = SmolLM()

    // regex to replace <think> tags with <blockquote>
    // to render them correctly in Markdown
    private val findThinkTagRegex = Regex("<think>(.*?)</think>")
    var responseGenerationsSpeed: Float? = null
    var responseGenerationTimeSecs: Int? = null
    val markwon: Markwon

    init {
        _currChatState.value = chatsDB.loadDefaultChat()
        val prism4j = Prism4j(PrismGrammarLocator())
        markwon =
            Markwon
                .builder(context)
                .usePlugin(CorePlugin.create())
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDarkula.create()))
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(
                    JLatexMathPlugin.create(
                        12f,
                        JLatexMathPlugin.BuilderConfigure {
                            it.inlinesEnabled(true)
                            it.blocksEnabled(true)
                        },
                    ),
                ).usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(
                    object : AbstractMarkwonPlugin() {
                        override fun configureTheme(builder: MarkwonTheme.Builder) {
                            val jetbrainsMonoFont =
                                ResourcesCompat.getFont(context, R.font.jetbrains_mono)!!
                            builder
                                .codeBlockTypeface(
                                    ResourcesCompat.getFont(context, R.font.jetbrains_mono)!!,
                                ).codeBlockTextColor(Color.WHITE)
                                .codeBlockTextSize(spToPx(10f))
                                .codeBlockBackgroundColor(Color.BLACK)
                                .codeTypeface(jetbrainsMonoFont)
                                .codeTextSize(spToPx(10f))
                                .codeTextColor(Color.WHITE)
                                .codeBackgroundColor(Color.BLACK)
                                .isLinkUnderlined(true)
                        }
                    },
                ).build()
        CoroutineScope(Dispatchers.Default).launch {
            Rag.load(
                context,
                chunksFile = FileUtilities.copyAssetToFile(context, "chunks.csv"),
                vectorsFile = FileUtilities.copyAssetToFile(context, "granite-30m_embeddings.csv"),
                modelFile = FileUtilities.copyAssetToFile(context, "granite-30m.onnx"),
                embeddingTokenizerFile = FileUtilities.copyAssetToFile(context, "granite-30m_tokenizer.json"),
                rerankerProps = RerankingProps(
                    tokenizer = FileUtilities.copyAssetToFile(context, "reranker_tokenizer.json"),
                    model = FileUtilities.copyAssetToFile(context, "mxbai-reranker-xsmall.onnx")
                ),
                // Some embedding models doesn't use TokenTypeIds (one of output tensors from the tokenizer) as input
                useTokenTypeIds = false
            )
        }
    }

    private fun spToPx(sp: Float): Int =
        TypedValue
            .applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
            .toInt()

    fun getChats(): Flow<List<Chat>> = chatsDB.getChats()

    fun getChatMessages(chatId: Long): Flow<List<ChatMessage>> = messagesDB.getMessages(chatId)

    fun updateChatLLM(modelId: Long) {
        _currChatState.value = _currChatState.value?.copy(llmModelId = modelId)
        chatsDB.updateChat(_currChatState.value!!)
    }

    fun updateChat(chat: Chat) {
        _currChatState.value = chat
        chatsDB.updateChat(chat)
        CoroutineScope(Dispatchers.Default).launch {
            loadModel()
        }
    }

    fun sendUserQuery(query: String) {

        _partialResponse.value = ""

        _currChatState.value?.let { chat ->

            messagesDB.deleteMessages(chat.id)

            CoroutineScope(Dispatchers.Default).launch {
                // Wait for the model to load
                val modelLoaded = loadModel()
                if (!modelLoaded) {
                    // If model loading failed or dialog was shown, stop here
                    return@launch
                }

                // Proceed only if model is loaded successfully
                chat.dateUsed = Date()
                chatsDB.updateChat(chat)
                if (chat.isTask) {
                    messagesDB.deleteMessages(chat.id)
                }
                withContext(Dispatchers.Main) {
                    _isGeneratingResponse.value = true
                }
                responseGenerationJob = launch {
                    val ragPrompt = Rag.getPrompt(query)
                    val chatMessage = "Context ${ragPrompt.contexts[0]}<br><br>" +
                            "Context ${ragPrompt.contexts[1]}<br><br>" +
                            "Query: ${ragPrompt.query}"
                    messagesDB.addUserMessage(chat.id, chatMessage)
                    withContext(Dispatchers.Main) {
                        _partialResponse.value = ""
                    }
                    try {
                        val responseDuration = measureTime {
                            smolLM.getResponse(ragPrompt.userMessage).collect {
                                withContext(Dispatchers.Main) {
                                    _partialResponse.value += it
                                }
                            }
                        }
                        // Replace <think> tags with <blockquote> tags
                        val finalResponse = findThinkTagRegex.replace(_partialResponse.value) { matchResult ->
                            "<blockquote>${matchResult.groupValues[1]}</blockquote>"
                        }
                        withContext(Dispatchers.Main) {
                            _partialResponse.value = finalResponse
                        }
                        messagesDB.addAssistantMessage(chat.id, _partialResponse.value)
                        chatsDB.updateChat(chat.copy(contextSizeConsumed = smolLM.getContextLengthUsed()))
                        withContext(Dispatchers.Main) {
                            _isGeneratingResponse.value = false
                            responseGenerationsSpeed = smolLM.getResponseGenerationSpeed()
                            responseGenerationTimeSecs = responseDuration.inWholeSeconds.toInt()
                        }
                    } catch (e: CancellationException) {
                        // Ignore CancellationException
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _partialResponse.value = ""
                            _isGeneratingResponse.value = false
                            createAlertDialog(
                                dialogTitle = "An error occurred",
                                dialogText = "The app is unable to process the query. The error message is: ${e.message}",
                                dialogPositiveButtonText = "Change model",
                                onPositiveButtonClick = {},
                                dialogNegativeButtonText = "",
                                onNegativeButtonClick = {},
                            )
                        }
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        _isGeneratingResponse.value = false
        _partialResponse.value = ""
        responseGenerationJob?.let { job ->
            if (job.isActive) {
                job.cancel()
            }
        }
    }

    fun switchChat(chat: Chat) {
        stopGeneration()
        _currChatState.value = chat
    }

    fun deleteChat(chat: Chat) {
        stopGeneration()
        chatsDB.deleteChat(chat)
        messagesDB.deleteMessages(chat.id)
        _currChatState.value = null
    }

    fun deleteModel(modelId: Long) {
        modelsRepository.deleteModel(modelId)
        if (_currChatState.value?.llmModelId == modelId) {
            _currChatState.value = _currChatState.value?.copy(llmModelId = -1)
        }
    }

    /**
     * Load the model for the current chat. If chat is configured with a LLM (i.e. chat.llModelId !=
     * -1), then load the model. If not, show the model list dialog. Once the model is finalized,
     * read the system prompt and user messages from the database and add them to the model.
     */
    suspend fun loadModel(): Boolean {
        // clear resources occupied by the previous model
        smolLM.close()
        _currChatState.value?.let { chat ->
            if (chat.llmModelId == -1L) {
                withContext(Dispatchers.Main) {
                    _showSelectModelListDialogState.value = true
                }
                return false // Model not loaded
            } else {
                val model = modelsRepository.getModelFromId(chat.llmModelId)
                if (model != null) {
                    _modelLoadState.value = ModelLoadingState.IN_PROGRESS
                    return try {
                        smolLM.create(
                            model.path,
                            chat.minP,
                            chat.temperature,
                            !chat.isTask,
                            chat.contextSize.toLong(),
                        )
                        LOGD("Model loaded")
                        if (chat.systemPrompt.isNotEmpty()) {
                            smolLM.addSystemPrompt(chat.systemPrompt)
                            LOGD("System prompt added")
                        }
                        withContext(Dispatchers.Main) {
                            _modelLoadState.value = ModelLoadingState.SUCCESS
                        }
                        true // Model loaded successfully
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _modelLoadState.value = ModelLoadingState.FAILURE
                            createAlertDialog(
                                dialogTitle = context.getString(R.string.dialog_err_title),
                                dialogText = context.getString(R.string.dialog_err_text, e.message),
                                dialogPositiveButtonText = context.getString(R.string.dialog_err_change_model),
                                onPositiveButtonClick = { showSelectModelListDialog() },
                                dialogNegativeButtonText = context.getString(R.string.dialog_err_close),
                                onNegativeButtonClick = {},
                            )
                        }
                        false // Model failed to load
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _showSelectModelListDialogState.value = true
                    }
                    return false // Model not loaded
                }
            }
        }
        return false // No chat state, model not loaded
    }

    override fun onCleared() {
        super.onCleared()
        smolLM.close()
    }

    fun showContextLengthUsageDialog() {
        _currChatState.value?.let { chat ->
            createAlertDialog(
                dialogTitle = context.getString(R.string.dialog_ctx_usage_title),
                dialogText =
                    context.getString(
                        R.string.dialog_ctx_usage_text,
                        chat.contextSizeConsumed.toString(),
                        chat.contextSize.toString(),
                    ),
                dialogPositiveButtonText = context.getString(R.string.dialog_ctx_usage_close),
                onPositiveButtonClick = {},
                dialogNegativeButtonText = null,
                onNegativeButtonClick = null,
            )
        }
    }

    fun showSelectModelListDialog() {
        _showSelectModelListDialogState.value = true
    }

    fun hideSelectModelListDialog() {
        _showSelectModelListDialogState.value = false
    }

    fun showMoreOptionsPopup() {
        _showMoreOptionsPopupState.value = true
    }

    fun hideMoreOptionsPopup() {
        _showMoreOptionsPopupState.value = false
    }

    fun showTaskListBottomList() {
        _showTaskListBottomListState.value = true
    }

    fun hideTaskListBottomList() {
        _showTaskListBottomListState.value = false
    }
}
