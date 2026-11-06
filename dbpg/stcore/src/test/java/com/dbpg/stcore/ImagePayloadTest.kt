package com.dbpg.stcore

import com.dbpg.stcore.imagegen.Base64Codec
import com.dbpg.stcore.imagegen.ImagePayloadExtractor
import com.dbpg.stcore.imagegen.ImagePayloadExtractor.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImagePayloadTest {

    // 1x1 透明 PNG
    private val pngB64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    private val pngBytes: ByteArray get() = Base64Codec.decode(pngB64)

    private fun makeZip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ---------------------------------------------------------- Base64

    @Test
    fun `base64 标准解码`() {
        assertTrue(Base64Codec.decode("aGVsbG8=").contentEquals("hello".toByteArray()))
    }

    @Test
    fun `base64 无 padding 与换行也能解`() {
        assertTrue(Base64Codec.decode("aGVs\nbG8").contentEquals("hello".toByteArray()))
    }

    @Test
    fun `base64 url-safe 映射`() {
        // "??>" -> Pz4+ standard / Pz4- url-safe
        assertTrue(Base64Codec.decode("Pz4-").contentEquals(Base64Codec.decode("Pz4+")))
    }

    // ---------------------------------------------------------- magic bytes

    @Test
    fun `文件头识别常见图片`() {
        assertEquals("image/png", ImagePayloadExtractor.sniffMime(pngBytes))
        assertEquals("image/jpeg", ImagePayloadExtractor.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", ImagePayloadExtractor.sniffMime("GIF89a".toByteArray()))
        assertEquals("image/bmp", ImagePayloadExtractor.sniffMime(byteArrayOf(0x42, 0x4D, 1, 2)))
        assertNull(ImagePayloadExtractor.sniffMime("hello world".toByteArray()))
    }

    @Test
    fun `识别 zip 文件头`() {
        assertEquals(true, ImagePayloadExtractor.isZip(makeZip(mapOf("a.txt" to "x".toByteArray()))))
        assertEquals(false, ImagePayloadExtractor.isZip(pngBytes))
    }

    // ---------------------------------------------------------- 直接图片

    @Test
    fun `响应体就是图片`() {
        val r = ImagePayloadExtractor.extract("image/png", pngBytes)
        assertEquals("image", r.kind)
        assertEquals(1, r.images.size)
        assertTrue((r.images[0] as Source.Inline).bytes.contentEquals(pngBytes))
    }

    // ---------------------------------------------------------- ZIP

    @Test
    fun `ZIP 响应在内存解压出图片`() {
        val zip = makeZip(
            mapOf(
                "readme.txt" to "not an image".toByteArray(),
                "img/1.png" to pngBytes,
            ),
        )
        val r = ImagePayloadExtractor.extract("application/zip", zip)
        assertEquals("zip", r.kind)
        assertEquals(1, r.images.size)
        val inline = r.images[0] as Source.Inline
        assertEquals("image/png", inline.mime)
        assertTrue(inline.bytes.contentEquals(pngBytes))
    }

    @Test
    fun `ZIP 被标成 json 也照样解压 文件头优先`() {
        val zip = makeZip(mapOf("1.png" to pngBytes))
        // 中转站常把 zip 的 content-type 写错成 application/json
        val r = ImagePayloadExtractor.extract("application/json", zip)
        assertEquals("zip", r.kind)
        assertEquals(1, r.images.size)
    }

    @Test
    fun `ZIP 内多张图片全部提取`() {
        val zip = makeZip(mapOf("a.png" to pngBytes, "b.png" to pngBytes))
        val r = ImagePayloadExtractor.extract("application/octet-stream", zip)
        assertEquals("zip", r.kind)
        assertEquals(2, r.images.size)
    }

    // ---------------------------------------------------------- JSON

    @Test
    fun `OpenAI 风格 b64_json`() {
        val json = """{"created":1,"data":[{"b64_json":"$pngB64"}]}"""
        val r = ImagePayloadExtractor.extract("application/json", json.toByteArray())
        assertEquals("json", r.kind)
        assertEquals(1, r.images.size)
        assertTrue((r.images[0] as Source.Inline).bytes.contentEquals(pngBytes))
    }

    @Test
    fun `OpenAI 风格 url 返回远程地址`() {
        val json = """{"data":[{"url":"https://cdn.example.com/a.png"}]}"""
        val r = ImagePayloadExtractor.extract("application/json", json.toByteArray())
        assertEquals("json-url", r.kind)
        assertEquals(1, r.images.size)
        assertEquals("https://cdn.example.com/a.png", (r.images[0] as Source.Remote).url)
    }

    @Test
    fun `自定义 image 字段 data url`() {
        val json = """{"code":0,"image":"data:image/png;base64,$pngB64"}"""
        val r = ImagePayloadExtractor.extract("application/json", json.toByteArray())
        assertEquals("json", r.kind)
        assertEquals(1, r.images.size)
        assertEquals("image/png", (r.images[0] as Source.Inline).mime)
    }

    @Test
    fun `数组里多张 data url`() {
        val json = """[{"images":["data:image/png;base64,$pngB64","data:image/png;base64,$pngB64"]}]"""
        val r = ImagePayloadExtractor.extract("application/json", json.toByteArray())
        assertEquals(2, r.images.size)
    }

    // ---------------------------------------------------------- data url / base64

    @Test
    fun `响应体是 data url 文本`() {
        val text = "data:image/png;base64,$pngB64"
        val r = ImagePayloadExtractor.extract("text/plain", text.toByteArray())
        assertEquals("dataurl", r.kind)
        assertEquals(1, r.images.size)
    }

    @Test
    fun `响应体是纯 base64 文本`() {
        val r = ImagePayloadExtractor.extract("text/plain", pngB64.toByteArray())
        assertEquals("base64", r.kind)
        assertEquals(1, r.images.size)
    }

    // ---------------------------------------------------------- 错误文本

    @Test
    fun `中转站报错文本如实回传`() {
        val text = """{"error":{"message":"invalid api key"}}"""
        val r = ImagePayloadExtractor.extract("application/json", text.toByteArray())
        assertEquals("text", r.kind)
        assertTrue(r.isEmpty)
        assertTrue(r.rawText!!.contains("invalid api key"))
    }

    @Test
    fun `空响应体`() {
        assertEquals("empty", ImagePayloadExtractor.extract(null, ByteArray(0)).kind)
    }
}
