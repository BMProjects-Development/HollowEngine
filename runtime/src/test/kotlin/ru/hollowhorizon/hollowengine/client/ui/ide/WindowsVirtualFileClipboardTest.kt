package ru.hollowhorizon.hollowengine.client.ui.ide

import com.sun.jna.Native
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.*

class WindowsVirtualFileClipboardTest {
    @Test
    fun `JNA can access Windows clipboard structure layouts`() {
        val formatSize = if (Native.POINTER_SIZE == Long.SIZE_BYTES) 32 else 20
        val mediumSize = if (Native.POINTER_SIZE == Long.SIZE_BYTES) 24 else 12

        assertEquals(formatSize, WindowsClipboardFormat().size())
        assertEquals(mediumSize, WindowsStorageMedium().size())
    }

    @Test
    fun `parses wide file group descriptors from shell clipboard`() {
        val bytes = descriptorGroup(
            DescriptorSpec("archive\\scripts\\main.kts", size = 42),
            DescriptorSpec("archive\\empty", directory = true),
        )

        val descriptors = parseWindowsFileGroupDescriptor(bytes)

        assertEquals(2, descriptors.size)
        assertEquals("archive\\scripts\\main.kts", descriptors[0].name)
        assertEquals(42, descriptors[0].size)
        assertFalse(descriptors[0].directory)
        assertEquals("archive\\empty", descriptors[1].name)
        assertTrue(descriptors[1].directory)
        assertNull(descriptors[1].size)
    }

    @Test
    fun `rejects truncated descriptor groups`() {
        val bytes = descriptorGroup(DescriptorSpec("first.txt"))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 2)

        assertTrue(parseWindowsFileGroupDescriptor(bytes).isEmpty())
    }

    @Test
    fun `reads Unicode disk paths from native file drop format`() {
        val paths = listOf(
            "C:\\Temp\\Архив\\изображение.png",
            "D:\\Projects\\script.kts",
        )

        val files = collectWindowsDropFiles(
            count = paths.size,
            pathLength = { index -> paths[index].length },
            readPath = { index, destination ->
                paths[index].toCharArray().copyInto(destination)
                paths[index].length
            },
        )

        assertEquals(paths, files.map { it.path })
    }

    @Test
    fun `skips invalid paths in native file drop format`() {
        val files = collectWindowsDropFiles(
            count = 2,
            pathLength = { index -> if (index == 0) 0 else 5 },
            readPath = { _, _ -> 0 },
        )

        assertTrue(files.isEmpty())
        assertTrue(collectWindowsDropFiles(-1, { 0 }, { _, _ -> 0 }).isEmpty())
    }

    @Test
    fun `plans nested files under one unique root and rejects unsafe names`() {
        val target = Files.createTempDirectory("hollowengine-virtual-paste-test-")
        try {
            Files.createDirectory(target.resolve("archive"))
            val descriptors = listOf(
                WindowsVirtualFileDescriptor("archive", directory = true, size = null),
                WindowsVirtualFileDescriptor("archive\\scripts\\main.kts", directory = false, size = 7),
                WindowsVirtualFileDescriptor("texture.png", directory = false, size = 8),
                WindowsVirtualFileDescriptor("..\\outside.txt", directory = false, size = 9),
                WindowsVirtualFileDescriptor("C:\\outside.txt", directory = false, size = 10),
                WindowsVirtualFileDescriptor("/outside.txt", directory = false, size = 11),
                WindowsVirtualFileDescriptor("texture.png", directory = false, size = 12),
            )

            val entries = planWindowsVirtualFilePaste(descriptors, target)

            assertEquals(listOf(0, 1, 2), entries.map(WindowsVirtualFilePasteEntry::sourceIndex))
            assertEquals(
                listOf("archive copy", "archive copy/scripts/main.kts", "texture.png"),
                entries.map { target.relativize(it.destination).invariantSeparatorsPathString },
            )
        } finally {
            target.toFile().deleteRecursively()
        }
    }

    private fun descriptorGroup(vararg descriptors: DescriptorSpec): ByteArray {
        val descriptorSize = 592
        val bytes = ByteArray(Int.SIZE_BYTES + descriptorSize * descriptors.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, descriptors.size)
        descriptors.forEachIndexed { index, descriptor ->
            val offset = Int.SIZE_BYTES + descriptorSize * index
            if (descriptor.size != null) {
                buffer.putInt(offset, 0x40)
                buffer.putInt(offset + 64, (descriptor.size ushr 32).toInt())
                buffer.putInt(offset + 68, descriptor.size.toInt())
            }
            if (descriptor.directory) buffer.putInt(offset + 36, 0x10)
            val name = descriptor.name.toByteArray(Charsets.UTF_16LE)
            require(name.size < 520)
            name.copyInto(bytes, offset + 72)
        }
        return bytes
    }

    private data class DescriptorSpec(
        val name: String,
        val directory: Boolean = false,
        val size: Long? = null,
    )
}
