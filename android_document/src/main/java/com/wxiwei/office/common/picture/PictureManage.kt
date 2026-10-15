/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
/*
 * 文件名称:           PictureManage.java
 *  
 * 编译器:             android2.2
 * 时间:               上午10:40:46
 */
package com.wxiwei.office.common.picture

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import com.wxiwei.office.fc.hslf.usermodel.PictureData
import com.wxiwei.office.fc.openxml4j.opc.PackagePart
import com.wxiwei.office.system.IControl
import com.wxiwei.office.system.OfficeCoroutineExecutor.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * manage picture
 * 
 * 
 * 
 * 
 * Read版本:       Read V1.0
 * 
 * 
 * 作者:           jhy1790
 * 
 * 
 * 日期:           2012-3-31
 * 
 * 
 * 负责人:         jhy1790
 * 
 * 
 * 负责小组:
 * 
 * 
 * 
 * 
 */
class PictureManage
    (control: IControl) {
    /**
     * add picture to pgMode
     * @param pgModel
     * @param picPart
     * @throws Exception
     */
    @Throws(Exception::class)
    fun addPicture(picPart: PackagePart): Int {
        val key = picPart.getPartName().getName()
        val index = picIndexs.get(key)
        if (index == null) {
            val picture = Picture()
            // picture data 
            picture.tempFilePath = writeTempFile(picPart)
            picture.setPictureType(picPart.getPartName().getExtension())
            val size = pictures!!.size
            pictures.add(picture)
            picIndexs.put(key, size)

            return size
        }
        return index
    }

    /**
     * add picture with PowerPoint
     * @param pgModel
     * @param pData
     * @return
     */
    fun addPicture(pData: PictureData): Int {
        val index = picIndexs.get(pData.tempFilePath)
        if (index == null) {
            val picture = Picture()
            // 图片数据
            picture.tempFilePath = pData.tempFilePath
            // 图片类型
            picture.pictureType = pData.getType().toByte()

            val size = pictures!!.size
            pictures.add(picture)

            picIndexs.put(pData.tempFilePath, size)
            return size
        }
        return index
    }

    /**
     * add picture to Excel
     * @param pgModel
     * @param pData
     * @return
     */
    fun addPicture(picture: Picture): Int {
        // 图片数据
        if (picture.tempFilePath == null) {
            picture.tempFilePath = writeTempFile(requireNotNull(picture.data))
            picture.data = null
        } else {
            val index = getPictureIndex(picture.tempFilePath)
            if (index >= 0) {
                return index
            }
        }

        val size = pictures!!.size
        pictures.add(picture)

        picIndexs.put(picture.tempFilePath, size)

        return size
    }

    /**
     * 
     */
    fun getPicture(index: Int): Picture? {
        if (index < 0 || index >= pictures!!.size) {
            return null
        }
        return pictures.get(index)
    }

    /**
     * 
     */
    fun getPictureIndex(key: String?): Int {
        val a = picIndexs.get(key)
        if (a == null) {
            return -1
        }
        return a
    }

    /**
     * 
     */
    fun writeTempFile(b: ByteArray): String? {
        try {
            return writeTempFile(b, 0, b.size)
        } catch (e: Exception) {
            control!!.getSysKit().getErrorKit().writerLog(e)
        }
        return null
    }

    fun writeTempFile(`in`: InflaterInputStream): String {
        val name = System.currentTimeMillis().toString() + ".tmp"
        val file = File(picTempPath + File.separator + name)
        try {
            file.createNewFile()
            val out = FileOutputStream(file)

            val buf = ByteArray(4096)
            var readBytes: Int
            while ((`in`.read(buf).also { readBytes = it }) > 0) {
                out.write(buf, 0, readBytes)
            }
        } catch (e: Exception) {
            control!!.getSysKit().getErrorKit().writerLog(e)
        }
        return file.getAbsolutePath()
    }

    /**
     * 
     */
    fun writeTempFile(b: ByteArray?, offset: Int, len: Int): String {
        val name = System.currentTimeMillis().toString() + ".tmp"
        val file = File(picTempPath + File.separator + name)
        try {
            file.createNewFile()
            val out = FileOutputStream(file)
            out.write(b, offset, len)
            out.close()
        } catch (e: Exception) {
            control!!.getSysKit().getErrorKit().writerLog(e)
        }
        return file.getAbsolutePath()
    }

    /**
     * get picture data
     */
    private fun writeTempFile(picPart: PackagePart?): String? {
        try {
            if (picPart != null) {
                val name = System.currentTimeMillis().toString() + ".tmp"
                val file = File(picTempPath + File.separator + name)
                file.createNewFile()
                val out = FileOutputStream(file)
                // data
                val `in` = picPart.getInputStream()
                var len: Int
                val b = ByteArray(8192)
                while ((`in`.read(b, 0, b.size).also { len = it }) != -1) {
                    out.write(b, 0, len)
                }
                `in`.close()
                out.close()

                return file.getAbsolutePath()
            }
        } catch (e: Exception) {
            control!!.getSysKit().getErrorKit().writerLog(e)
        }
        return null
    }

    /**
     * 
     */
    @Synchronized
    fun getBitmap(key: String?): Bitmap? {
        return bitmaps.get(key)
    }

    /**
     * 
     */
    @Synchronized
    fun addBitmap(key: String?, bitmap: Bitmap) {
        val replaced: Bitmap? = bitmaps.remove(key)
        if (replaced != null) {
            bitmapTotalCacheSize -= replaced.getAllocationByteCount().toLong()
        }
        // Least recently drawn first (access-ordered map), so the pictures on screen stay.
        // Evicted bitmaps are not recycled: another thread (UI, thumbnails, converters) may be
        // drawing one right now. Since API 26 the pixels live with the Bitmap and the GC frees them.
        val eldest: MutableIterator<MutableMap.MutableEntry<String?, Bitmap?>?> =
            bitmaps.entries.iterator()
        while (bitmapTotalCacheSize + bitmap.getAllocationByteCount() > CACHE_BYTES && eldest.hasNext()) {
            bitmapTotalCacheSize -= eldest.next()!!.value!!.getAllocationByteCount().toLong()
            eldest.remove()
        }
        bitmapTotalCacheSize += bitmap.getAllocationByteCount().toLong()
        bitmaps.put(key, bitmap)
    }

    private fun checkPictureConverterMgr() {
        if (picConverterMgr == null) {
            picConverterMgr = PictureConverterMgr(control)
        } else {
            picConverterMgr!!.control = control
        }
    }

    /**
     * 
     * @param path
     * @return
     */
    fun isConverting(path: String?): Boolean {
        checkPictureConverterMgr()
        return picConverterMgr!!.isPictureConverting(path)
    }

    /**
     * 
     * @param viewIndex
     * @return
     */
    fun hasConvertingVectorgraph(viewIndex: Int): Boolean {
        checkPictureConverterMgr()
        return picConverterMgr!!.hasConvertingVectorgraph(viewIndex)
    }


    /**
     * call when vector graph has been converted, but with different view index
     * eg. different pages has same vector graph
     * @param path
     * @param viewIndex
     */
    fun appendViewIndex(path: String?, viewIndex: Int) {
        checkPictureConverterMgr()
        if (picConverterMgr != null) {
            picConverterMgr!!.appendViewIndex(path, viewIndex)
        }
    }

    /**
     * convert vector graph to normal png
     * @param imageType
     * @param path
     * @param width
     * @param height
     * @return
     */
    fun convertVectorgraphToPng(
        viewIndex: Int,
        imageType: Byte,
        path: String,
        width: Int,
        height: Int,
        singleThread: Boolean
    ): String {
        val convertToPath = path.substring(0, path.length - 4) + "converted.tmp"

        checkPictureConverterMgr()

        picConverterMgr!!.addConvertPicture(
            viewIndex,
            imageType,
            path,
            convertToPath,
            width,
            height,
            singleThread
        )

        return convertToPath
    }

    /**
     * convert some image which load fail in android OS to normal png
     * @param viewIndex
     * @param path
     * @param picType
     * @param singleThread
     * @return
     */
    fun convertToPng(
        viewIndex: Int,
        path: String,
        picType: String?,
        singleThread: Boolean
    ): String {
        val convertToPath = path.substring(0, path.length - 4) + "converted.tmp"

        checkPictureConverterMgr()

        picConverterMgr!!.addConvertPicture(viewIndex, path, convertToPath, picType, singleThread)

        return convertToPath
    }

    /**
     * 
     */
    @Synchronized
    fun clearBitmap() {
        // Not recycled, see addBitmap.
        bitmaps.clear()
        bitmapTotalCacheSize = 0
    }

    /**
     * has stored bitmap
     * @return
     */
    @Synchronized
    fun hasBitmap(): Boolean {
        return bitmaps.size > 0
    }


    /**
     * 
     */
    fun getPicTempPath(): String {
        return picTempPath!!
    }

    /**
     * 
     */
    private fun deleteTempFile(folder: File) {
        if (!folder.exists()) {
            return
        }
        val files = folder.listFiles()
        if (files != null) {
            for (file in files) {
                file.delete()
            }
        }
        folder.delete()
    }

    /**
     * 
     * @param bitmap
     * @param picType
     * @param fileName
     * @return
     */
    fun saveBitmapToFile(bitmap: Bitmap, picType: CompressFormat, fileName: String?): Boolean {
        val file = File(picTempPath + File.separatorChar + fileName + ".jpg")
        try {
            if (file.exists()) {
                file.delete()
            }
            file.createNewFile()
            val fOut = FileOutputStream(file)
            bitmap.compress(picType, 100, fOut)
            fOut.flush()
            fOut.close()
        } catch (e: Exception) {
            control!!.getSysKit().getErrorKit().writerLog(e)
            return false
        }
        return true
    }

    /**
     * 
     */
    fun dispose() {
        clearBitmap()
        if (pictures != null) {
            for (picture in pictures) {
                picture.dispose()
            }
            pictures.clear()
            //pictures = null;
        }
        picIndexs.clear()
        control = null
        //picIndexs = null;
        //kit = null;
        val folder = picTempPath?.let(::File)
        try {
            if (picConverterMgr != null) {
                picConverterMgr!!.dispose()
            }

            launch(object : Runnable {
                override fun run() {
                    try {
                        if (folder != null) deleteTempFile(folder)
                    } catch (e: Exception) {
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    //
    private var picTempPath: String? = null

    // use shape
    private val picIndexs: MutableMap<String?, Int?>

    //
    private val pictures: MutableList<Picture>?

    //
    private var control: IControl?
    private var picConverterMgr: PictureConverterMgr? = null

    //
    init {
        this.control = control
        pictures = ArrayList<Picture>()
        picIndexs = HashMap<String?, Int?>()
        var file = control.getMainFrame().getTemporaryDirectory()
        if (file == null) {
            control.getSysKit().getErrorKit().writerLog(Throwable("SD Card Error"))
        } else {
            picTempPath = file.getAbsolutePath() + File.separator + "tempPic"
            file = File(requireNotNull(picTempPath))
            if (!file.exists()) {
                file.mkdir()
            }
            picTempPath = file.getAbsolutePath() + File.separator + System.currentTimeMillis()
            file = File(requireNotNull(picTempPath))
            if (!file.exists()) {
                file.mkdir()
            }
        }
    }

    companion object {
        //
        /**
         * Budget of the decoded picture cache, in bytes: a quarter of the heap, capped at 128 MB.
         * The old fixed 8M pixels could not hold a single 12 MP photo, so the pictures on screen
         * were evicted and decoded again while drawing.
         */
        private val CACHE_BYTES = min(Runtime.getRuntime().maxMemory() / 4, 128L * 1024 * 1024)

        //
        private var bitmapTotalCacheSize: Long = 0

        //
        private val bitmaps: MutableMap<String?, Bitmap?> =
            LinkedHashMap<String?, Bitmap?>(16, 0.75f, true)
    }
}
