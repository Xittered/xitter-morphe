package app.xitter.patches.network

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.xitter.patches.shared.Constants.COMPATIBILITY_X
import app.xitter.patches.shared.Constants.CONFIG_CLASS
import app.xitter.patches.shared.Constants.EXTENSION_PACKAGE
import app.xitter.patches.shared.Constants.NETWORK_CLASS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.net.URI
import java.util.logging.Logger
import org.w3c.dom.Element

private const val OKHTTP_BUILDER = "Lokhttp3/OkHttpClient\$Builder;"
private const val WEBVIEW = "Landroid/webkit/WebView;"
private const val CONTEXT = "Landroid/content/Context;"


/**
 * A platform method the app calls that the guard takes over: calls to it in the app are
 * replaced by a static call to [NETWORK_CLASS] with the receiver (if any) as first argument,
 * so the registers stay the same.
 */
private class Redirect(
    val definingClasses: (String) -> Boolean,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val static: Boolean,
    /** the receiver's type in the replacement (subclasses are passed as it) */
    val receiver: String? = null,
) {
    fun matches(ref: MethodReference) =
        ref.name == name && ref.returnType == returnType && ref.parameterTypes.map { it.toString() } == parameters &&
            definingClasses(ref.definingClass)

    // URL is final; the app's WebView subclasses are passed as WebView
    fun replacement(ref: MethodReference) = ImmutableMethodReference(
        NETWORK_CLASS,
        name,
        if (static) parameters else listOf(receiver ?: if (ref.definingClass == "Ljava/net/URL;") ref.definingClass else WEBVIEW) + parameters,
        returnType,
    )
}

private val networkSecurityPatch = resourcePatch {
    execute {
        // Plain http to the instance (a LAN or dev server) and certificates the user installed
        // (an instance with its own CA). X's app already allows cleartext; it has no config.
        get("res/xml").mkdirs()
        get("res/xml/xitter_network_security_config.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="true">
                    <trust-anchors>
                        <certificates src="system" />
                        <certificates src="user" />
                    </trust-anchors>
                </base-config>
            </network-security-config>
            """.trimIndent(),
        )
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:networkSecurityConfig", "@xml/xitter_network_security_config")
            application.setAttribute("android:usesCleartextTraffic", "true")
        }
    }
}

@Suppress("unused")
val xitterPatch = bytecodePatch(
    name = "Xitter",
    description = "Makes the app a client of a Xitter instance. Every address of X (x.com, twitter.com, " +
        "twimg.com, t.co...) is sent to the instance instead, signed in with the instance's sessions, " +
        "and the app's sign-in screens sign in to the instance. Nothing is sent to X.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_X)
    dependsOn(networkSecurityPatch)
    extendWith("extensions/xitter.mpe")

    val serverUrl by stringOption(
        key = "serverUrl",
        default = "https://",
        title = "Instance address",
        description = "The address of the Xitter instance, as you open it in a browser " +
            "(for example https://xitter.example or http://192.168.1.5:3000).",
        required = true,
    ) {
        it != null && runCatching {
            val u = URI(it.trim())
            (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() && u.rawQuery == null
        }.getOrDefault(false)
    }

    val clientToken by stringOption(
        key = "clientToken",
        default = "",
        title = "Client app token",
        description = "Optional. A token from the instance's Admin > Client apps (xitter_...). " +
            "Posts made from the app are then labelled with that app's name, such as \"Xitter for Android\".",
    ) { it.isNullOrEmpty() || it.trim().matches(Regex("^xitter_[0-9a-f]{64}$")) }

    val blockThirdParty by booleanOption(
        key = "blockThirdParty",
        default = true,
        title = "Block third-party services",
        description = "Also refuse connections to hosts that are neither X's nor the instance's " +
            "(analytics, ads, crash reporting, payment and identity SDKs). X's own hosts are always refused.",
    )

    val allowedHosts by stringOption(
        key = "allowedHosts",
        default = "",
        title = "Allowed hosts",
        description = "Optional. Comma separated hosts the app may still reach while third-party services " +
            "are blocked, e.g. a separate media domain of the instance.",
    )

    execute {
        // 1. The options, baked into the extension.
        val config = mutableClassDefBy(CONFIG_CLASS)
        fun MutableMethod.returnString(value: String) = addInstructions(
            0,
            """
                const-string v0, "${value.smaliEscaped()}"
                return-object v0
            """,
        )
        config.methods.single { it.name == "serverUrl" }.returnString(serverUrl!!.trim().trimEnd('/'))
        config.methods.single { it.name == "clientToken" }.returnString(clientToken?.trim() ?: "")
        config.methods.single { it.name == "allowedHosts" }.returnString(allowedHosts?.trim() ?: "")
        config.methods.single { it.name == "blockThirdParty" }.addInstructions(
            0,
            """
                const/4 v0, ${if (blockThirdParty != false) "0x1" else "0x0"}
                return v0
            """,
        )

        // 2. Every OkHttp client. All of them are made by OkHttpClient(Builder), including
        // OkHttpClient() and Builder.build(); the guard edits the builder before it is read.
        val clientConstructor = mutableClassDefBy("Lokhttp3/OkHttpClient;").methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf(OKHTTP_BUILDER)
        } ?: throw PatchException("OkHttpClient(Builder) not found")
        clientConstructor.addInstruction(0, "invoke-static {p1}, $NETWORK_CLASS->onBuild($OKHTTP_BUILDER)V")

        // 3. The native sign-in screens. Signed out, the app shows a Jetfuel (server-drawn) welcome
        // screen unless a feature switch picks the classic splash, whose task.json flow the
        // extension answers.
        // getVariant() returns the (obfuscated) OnboardingVariant enum: JETFUEL unless
        // "onboarding_new_killer_switch_enabled" is on; it now always returns DEFAULT
        val variantProviders = mutableListOf<ClassDef>()
        classDefForEach { c ->
            if (c.methods.any { it.isVariantGetter() }) variantProviders += c
        }
        if (variantProviders.isEmpty()) throw PatchException("OnboardingVariant getVariant() not found")
        for (c in variantProviders) {
            mutableClassDefBy(c).methods.filter { it.isVariantGetter() }.forEach {
                val variant = it.returnType
                it.addInstructions(
                    0,
                    """
                        sget-object v0, $variant->DEFAULT:$variant
                        return-object v0
                    """,
                )
            }
        }

        // The classic app, not X Lite: X Lite (com.x.android.*) reads GraphQL through Apollo
        // documents with aliases and the schema's own field names, which nothing on the instance
        // answers; the classic screens read the same timeline format as the web client. Every
        // yes/no question of the X Lite gate (com.twitter.x.lite.featureswitches.n) is answered no.
        val gate = classDefByOrNull("Lcom/twitter/x/lite/featureswitches/n;")
            ?: throw PatchException("X Lite gate not found")
        val questions = gate.methods.filter { it.returnType == "Z" }.map { it.name to it.parameterTypes.map { p -> p.toString() } }.toSet()
        var answered = 0
        classDefForEach { c ->
            if (gate.type !in c.interfaces) return@classDefForEach
            mutableClassDefBy(c).methods
                .filter { it.implementation != null && it.returnType == "Z" && (it.name to it.parameterTypes.map { p -> p.toString() }) in questions }
                .forEach {
                    it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                    answered++
                }
        }
        println("Xitter: X Lite gate answered no in $answered methods")

        // Errors X recovers from go to its crash service (blocked); XitterDebug can log them. The
        // reporter's methods are obfuscated, so it is found by shape and skipped if it changed.
        val reporter = mutableClassDefByOrNull("Lcom/twitter/util/errorreporter/e;")
        var hooked = 0
        reporter?.methods?.forEach {
            val params = it.parameterTypes.map { p -> p.toString() }
            if (it.returnType != "V" || it.implementation == null) return@forEach
            val register = if (AccessFlags.STATIC.isSet(it.accessFlags)) "p0" else "p1"
            if (params == listOf("Lcom/twitter/util/errorreporter/c;", "Z") || params == listOf("Ljava/lang/Throwable;")) {
                it.addInstruction(0, "invoke-static {$register}, ${EXTENSION_PACKAGE}XitterDebug;->report(Ljava/lang/Object;)V")
                hooked++
            }
        }
        println("Xitter: error reporter " + if (hooked > 0) "hooked ($hooked entry points)" else "not found, not hooked")

        // 4. Everything else that opens connections or resolves hosts.
        redirectPlatformCalls()
    }
}

private fun BytecodePatchContext.redirectPlatformCalls() {
    // WebView and the app's subclasses of it
    val superclasses = mutableMapOf<String, String?>()
    classDefForEach { superclasses[it.type] = it.superclass }
    // the framework's own classes aren't in the app; the Contexts the app derives from
    superclasses.putAll(
        mapOf(
            "Landroid/app/Activity;" to "Landroid/view/ContextThemeWrapper;",
            "Landroid/view/ContextThemeWrapper;" to "Landroid/content/ContextWrapper;",
            "Landroid/app/Service;" to "Landroid/content/ContextWrapper;",
            "Landroid/app/Application;" to "Landroid/content/ContextWrapper;",
            "Landroid/content/ContextWrapper;" to CONTEXT,
        ).filterKeys { it !in superclasses },
    )
    val subclassCache = HashMap<Pair<String, String>, Boolean>()
    fun isSubclass(type: String, of: String): Boolean = subclassCache.getOrPut(type to of) {
        var t: String? = type
        var depth = 0
        while (t != null && depth++ < 30) {
            if (t == of) return@getOrPut true
            t = superclasses[t]
        }
        false
    }
    fun isWebView(type: String) = isSubclass(type, WEBVIEW)
    fun isContext(type: String) = isSubclass(type, CONTEXT)

    val url: (String) -> Boolean = { it == "Ljava/net/URL;" }
    val inetAddress: (String) -> Boolean = { it == "Ljava/net/InetAddress;" }
    val redirects = listOf(
        Redirect(url, "openConnection", emptyList(), "Ljava/net/URLConnection;", static = false),
        Redirect(url, "openConnection", listOf("Ljava/net/Proxy;"), "Ljava/net/URLConnection;", static = false),
        Redirect(url, "openStream", emptyList(), "Ljava/io/InputStream;", static = false),
        Redirect(inetAddress, "getAllByName", listOf("Ljava/lang/String;"), "[Ljava/net/InetAddress;", static = true),
        Redirect(inetAddress, "getByName", listOf("Ljava/lang/String;"), "Ljava/net/InetAddress;", static = true),
        Redirect(::isWebView, "loadUrl", listOf("Ljava/lang/String;"), "V", static = false),
        Redirect(::isWebView, "loadUrl", listOf("Ljava/lang/String;", "Ljava/util/Map;"), "V", static = false),
        Redirect(::isWebView, "postUrl", listOf("Ljava/lang/String;", "[B"), "V", static = false),
        // links handed to other apps
        Redirect(::isContext, "startActivity", listOf("Landroid/content/Intent;"), "V", static = false, receiver = CONTEXT),
        Redirect(::isContext, "startActivity", listOf("Landroid/content/Intent;", "Landroid/os/Bundle;"), "V", static = false, receiver = CONTEXT),
    )
    val invokes = setOf(
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
    )

    // found in one pass and edited after, so classes are not changed while being read
    val targets = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        val calls = classDef.methods.any { method ->
            method.implementation?.instructions?.any { i ->
                i.opcode in invokes && ((i as ReferenceInstruction).reference as MethodReference).let { ref ->
                    redirects.any { it.matches(ref) && it.static == (i.opcode == Opcode.INVOKE_STATIC || i.opcode == Opcode.INVOKE_STATIC_RANGE) }
                }
            } == true
        }
        if (calls) targets += classDef
    }

    var count = 0
    for (classDef in targets) {
        val mutable = mutableClassDefBy(classDef)
        for (method in mutable.methods) {
            val instructions = method.implementation?.instructions ?: continue
            for (index in instructions.indices) {
                val i = instructions[index]
                if (i.opcode !in invokes) continue
                val ref = (i as ReferenceInstruction).reference as MethodReference
                val isStatic = i.opcode == Opcode.INVOKE_STATIC || i.opcode == Opcode.INVOKE_STATIC_RANGE
                val redirect = redirects.firstOrNull { it.matches(ref) && it.static == isStatic } ?: continue
                val to = redirect.replacement(ref)
                val replacement = when (i) {
                    is Instruction35c -> BuilderInstruction35c(
                        Opcode.INVOKE_STATIC, i.registerCount,
                        i.registerC, i.registerD, i.registerE, i.registerF, i.registerG, to,
                    )
                    is Instruction3rc -> BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, i.startRegister, i.registerCount, to)
                    else -> continue
                }
                method.replaceInstruction(index, replacement)
                count++
            }
        }
    }
    println("Xitter: routed $count network calls in ${targets.size} classes through the Xitter guard")

    // X's in-app web pages (settings pages) are the instance's (XitterNetwork.loadUrl), and the
    // app's WebViewClients hand any page outside X's hosts to the browser, where the instance
    // is signed out. A page of the instance's stays in the WebView.
    val clients = mutableListOf<ClassDef>()
    classDefForEach { c -> if (isSubclass(c.type, WEBVIEW_CLIENT) && c.methods.any { it.isUrlOverride() }) clients += c }
    var kept = 0
    for (c in clients) {
        mutableClassDefBy(c).methods.filter { it.isUrlOverride() }.forEach {
            it.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p2}, $NETWORK_CLASS->isInstancePage(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :original
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("original", it.getInstruction(0)),
            )
            kept++
        }
    }
    println("Xitter: instance pages stay in $kept web view clients")
}

private const val WEBVIEW_CLIENT = "Landroid/webkit/WebViewClient;"

// shouldOverrideUrlLoading(WebView, String or WebResourceRequest), with a register to spare
private fun Method.isUrlOverride(): Boolean {
    if (name != "shouldOverrideUrlLoading" || returnType != "Z" || AccessFlags.ABSTRACT.isSet(accessFlags)) return false
    val params = parameterTypes.map { it.toString() }
    if (params.size != 2 || params[0] != "Landroid/webkit/WebView;") return false
    if (params[1] != "Ljava/lang/String;" && params[1] != "Landroid/webkit/WebResourceRequest;") return false
    val impl = implementation ?: return false
    return impl.registerCount > 3
}

private fun Method.isVariantGetter() =
    name == "getVariant" && parameterTypes.isEmpty() && implementation?.instructions?.any {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((it as ReferenceInstruction).reference as StringReference).string == "onboarding_new_killer_switch_enabled"
    } == true

private fun String.smaliEscaped() = buildString {
    for (c in this@smaliEscaped) {
        when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c.code < 0x20 || c.code > 0x7e -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
