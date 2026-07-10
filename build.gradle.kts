plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.omnipilot"
version = "1.0.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.2.5")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf("org.jetbrains.plugins.terminal"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("") // Empty string = no upper bound — compatible with all future IDE versions
        pluginDescription.set("""
            <p>OmniPilot is an advanced, fully autonomous AI coding assistant plugin for JetBrains IDEs (IntelliJ IDEA, WebStorm, PyCharm, Android Studio, etc.). It brings the power of state-of-the-art Large Language Models directly into your editor with deep IDE integration.</p>
            <br/>
            <h3>Features</h3>
            <ul>
                <li><b>Multi-Provider Support</b>: Seamlessly connect to any OpenAI-compatible API, including OpenAI, Groq, Anthropic, NVIDIA NIM, Cloudflare, and local models via LM Studio or Ollama.</li>
                <li><b>Dynamic Model Fetching</b>: Automatically pull the latest available models from your configured provider directly within the IDE settings.</li>
                <li><b>Agent Mode (Auto-Pilot)</b>: Give OmniPilot a complex task and watch it autonomously execute terminal commands, read project files, and write code on your behalf.</li>
                <li><b>Granular Permissions System</b>: Full control over what the Agent can do. Review every file write via a rich Diff Viewer and approve or deny terminal commands before they execute.</li>
                <li><b>Smart Context</b>: Automatically attaches your currently focused file and selected text to the context window.</li>
                <li><b>Chat Mode</b>: Standard conversational AI mode with markdown rendering, syntax highlighting, and 1-click buttons to copy code or insert it directly at your cursor.</li>
                <li><b>Persistent State</b>: Your chat history, selected models, and configuration persist across IDE restarts.</li>
            </ul>
        """.trimIndent())
        changeNotes.set("""
            <ul>
                <li><b>1.0.0</b>: Initial release!
                    <ul>
                        <li>Multi-Provider Support (OpenAI, Anthropic, Groq, NVIDIA NIM, Local)</li>
                        <li>Fully Autonomous Agent Mode</li>
                        <li>Dynamic model fetching & Chat UI</li>
                    </ul>
                </li>
            </ul>
        """.trimIndent())
    }
    
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
