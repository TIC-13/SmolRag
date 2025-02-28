## SmolRag - SmolChat with RAG
This project is an evolution of [**SmolChat**](https://github.com/shubham0204/SmolChat-Android), running **Retrieval-Augmented Generation (RAG)** techniques locally to enhance the performance of LLMs in specific subject scenarios. It is ideal for situations where a specialized model is needed but unavailable, and fine-tuning isn't feasible, providing generic models with relevant in-context information.

#### **SmolChat Changelog:**
- Support of RAG for LLM response added
- Support of reranking for LLM response added
- Customization of system message disabled - On-Device Inference of SLMs in Android

#### SmolChat Project Goals

- Provide a usable user interface to interact with local SLMs (small language models) locally, on-device
- Allow users to add/remove SLMs (GGUF models) and modify their system prompts or inference parameters (temperature, 
  min-p)
- Allow users to create specific-downstream tasks quickly and use SLMs to generate responses
- Simple, easy to understand, extensible codebase

#### Setup

1. Clone the repository with its submodule originating from llama.cpp,

```commandline
git clone https://github.com/TIC-13/SmolRag.git
cd SmolRag
git submodule update --init --recursive
```

2. Android Studio starts building the project automatically. If not, select **Build > Rebuild Project** to start a project build.

3. After a successful project build, [connect an Android device](https://developer.android.com/studio/run/device) to your system. Once connected, the name of the device must be visible in top menu-bar in Android Studio.

#### Working

1. The application uses llama.cpp to load and execute GGUF models. As llama.cpp is written in pure C/C++, it is easy 
   to compile on Android-based targets using the [NDK](https://developer.android.com/ndk). 

2. The `smollm` module uses a `llm_inference.cpp` class which interacts with llama.cpp's C-style API to execute the 
   GGUF model and a JNI binding `smollm.cpp`. Check the [C++ source files here](./smollm/src/main/cpp). On the Kotlin side, the [`SmolLM`](./smollm/src/main/java/io/shubham0204/smollm/SmolLM.kt) class provides 
   the required methods to interact with the JNI (C++ side) bindings.

3. The `app` module contains the application logic and UI code. Whenever a new chat is opened, the app instantiates 
   the `SmolLM` class and provides it the model file-path which is stored by the [`LLMModel`](./app/src/main/java/io/shubham0204/smollmandroid/data/DataModels.kt) entity in the ObjectBox.
   Next, the app adds messages with role `user` and `system` to the chat by retrieving them from the database and
   using `LLMInference::addChatMessage`.

4. For tasks, the messages are not persisted, and we inform to `LLMInference` by passing `_storeChats=false` to
   `LLMInference::loadModel`.

<table>
<tr>
<td>
<img src="resources/app_screenshots/pic1.png" alt="app_img_01">
</td>
<td>
<img src="resources/app_screenshots/pic2.png" alt="app_img_02">
</td>
<td>
<img src="resources/app_screenshots/pic3.png" alt="app_img_03">
</td>
<td>
<img src="resources/app_screenshots/pic4.png" alt="app_img_03">
</td>
</tr>
<tr>
<td>
<img src="resources/app_screenshots/pic5.png" alt="app_img_04">
</td>
<td>
<img src="resources/app_screenshots/pic6.png" alt="app_img_05">
</td>
<td>
<img src="resources/app_screenshots/pic7.png" alt="app_img_06">
</td>
<td>
<img src="resources/app_screenshots/pic8.png" alt="app_img_07">
</td>
</tr>
</table>

#### Technologies

* [ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp) is a pure C/C++ framework to execute machine learning 
  models on multiple execution backends. It provides a primitive C-style API to interact with LLMs 
  converted to the [GGUF format](https://github.com/ggerganov/ggml/blob/master/docs/gguf.md) native to [ggml](https://github.com/ggerganov/ggml)/llama.cpp. The app uses JNI bindings to interact with a small class `smollm.
  cpp` which uses llama.cpp to load and execute GGUF models.

* [ObjectBox](https://objectbox.io) is a on-device, high-performance NoSQL database with bindings available in multiple 
  languages. The app 
  uses ObjectBox to store the model, chat and message metadata.

* [noties/Markwon](https://github.com/noties/Markwon) is a markdown rendering library for Android. The app uses 
  Markwon and [Prism4j](https://github.com/noties/Prism4j) (for code syntax highlighting) to render Markdown responses 
  from the SLMs.
