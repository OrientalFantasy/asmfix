package fix.rei.whiterasbk.bad

import com.sun.xml.internal.messaging.saaj.util.ByteInputStream
import org.objectweb.asm.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.name

fun main() {

    val output = File("output")

    Files.list(Paths.get("testmods")).forEach { modPath ->
        val originalModJar = ZipFile(modPath.toFile())
        val entries = originalModJar.entries()
        val destModJarPath = Paths.get(output.absolutePath, "(reobf)" + modPath.name)
        if (destModJarPath.exists()) Files.delete(destModJarPath)
        val destModJarOutputStream = ZipOutputStream(destModJarPath.toFile().outputStream())

        fun createDestEntry(name: String) = destModJarOutputStream.putNextEntry(ZipEntry(name))
        fun closeDestEntry() = destModJarOutputStream.closeEntry()
        fun writeDestBytes(bytes: ByteArray) = ByteInputStream(bytes, bytes.size).copyTo(destModJarOutputStream)

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val originalName = entry.name
            val originalBytes = originalModJar.getInputStream(entry).readBytes()

            when {
                originalName.endsWith(".class") -> {
                    createDestEntry(originalName)
                    val classReader = ClassReader(originalBytes)
                    val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)

                    val fieldRenamerVisitor = RemappingVisitor(
                        Opcodes.ASM9,
                        classWriter,
                        mapOf(),
                        mapOf("m_93696_" to ("isFocused" to 0)),
                        s = originalName
                    )

                    classReader.accept(fieldRenamerVisitor, ClassReader.EXPAND_FRAMES)
                    writeDestBytes(classWriter.toByteArray())
                }

                else -> {
                    createDestEntry(originalName)
                    writeDestBytes(originalBytes)
                }
            }

            closeDestEntry()
        }

        originalModJar.close()
        destModJarOutputStream.close()
    }
}

class RemappingVisitor(
    api: Int,
    cv: ClassVisitor,
    private val fieldMapping: Map<String, Pair<String, Int>>,
    private val methodMapping: Map<String, Pair<String, Int>>,
    private val appendMode: Boolean = false,
    val s : String,
) : ClassVisitor(api, cv) {


    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        return MethodTransformer(
            super.visitMethod(
                access,
                name.let {
                    if (it in methodMapping) {
                        val realName = getRealMethodName(name)
                        println("remapping method  `$name` to `$realName`")
                        realName
                    } else it
                },
                descriptor,
                signature,
                exceptions
            )
        )
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        return if (name in fieldMapping) {
            val realName = getRealFieldName(name)
            println("remapping $s field `$name` to `$realName`")

            if (appendMode) super.visitField(access, name, descriptor, signature, value)
            super.visitField(access, realName, descriptor, signature, value)
        } else {
            super.visitField(access, name, descriptor, signature, value)
        }
    }

    fun getRealFieldName(srgName: String): String {
        val (realName, _) = fieldMapping[srgName] ?: error("")
        return realName
    }

    fun getRealMethodName(srgName: String): String {
        val (realName, _) = methodMapping[srgName] ?: error("")
        return realName
    }

    inner class MethodTransformer(mv: MethodVisitor): MethodVisitor(api, mv) {

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String?) {
            return if (name in fieldMapping)
                super.visitFieldInsn(opcode, owner, getRealFieldName(name), descriptor).apply {
                    //println("$s -> $owner -> $descriptor")
                }
            else
                super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {

            return if (bootstrapMethodArguments.filterIsInstance<Handle>().any { it.name in methodMapping }) {

                val mapped = bootstrapMethodArguments.map { h ->
                    if (h is Handle) {
                        Handle(h.tag, h.owner, getRealMethodName(h.name), h.desc, h.isInterface)
                    } else h
                }

                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *mapped.toTypedArray())
            } else
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String?, isInterface: Boolean) {
            return if (name in methodMapping)
                super.visitMethodInsn(opcode, owner, getRealMethodName(name), descriptor, isInterface)
            else
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }
}
