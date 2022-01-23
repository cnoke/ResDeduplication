package com.cnoke.resplugin

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import pink.madis.apk.arsc.ResourceFile
import pink.madis.apk.arsc.ResourceTableChunk
import pink.madis.apk.arsc.StringPoolChunk
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

const val RESOURCES_NAME = "resources.arsc"
val mappingDir = "ResDeduplication${File.separator}mapping${File.separator}"
const val RES_DEDUPLION_MAPPING = "ResDeduplicationMapping.txt"
const val DIR_NAME = "ResDeduplication"

/**
 * @date on 2022/1/23
 * @author huanghui
 * @title
 * @describe
 */
class ResPlugin : Plugin<Project> {

    companion object{
        const val EXT_NAME = "ResDeduplication"

    }

    override fun apply(project: Project) {
        // 创建配置
        project.extensions.create(EXT_NAME, ResDeduplicationConfig::class.java)

        val isApp = project.plugins.hasPlugin(AppPlugin::class.java)
        if (isApp) {
            project.afterEvaluate {
                // 得到配置
                val config = project.extensions.findByName(EXT_NAME) as ResDeduplicationConfig
                // 判断是否启用插件
                if (!config.enable) {
                    return@afterEvaluate
                }
                val android = project.extensions.getByType(AppExtension::class.java)
                android.applicationVariants.forEach { variant ->
                    val variantName = variant.name.capitalize()
                    // 判断debug模式下是否开启使用
                    if (!config.enableWhenDebug && variantName == "Debug") {
                        // debug 模式不适用
                        return@forEach
                    }

                    val processResources = project.tasks.getByName("process${variantName}Resources")

                    processResources.doLast {

                        val resourcesTask = it as LinkApplicationAndroidResourcesTask

                        val files = resourcesTask.resPackageOutputFolder.asFileTree.files
                        files.filter { file->
                            file.name.endsWith(".ap_")
                        }.forEach { apFile ->
                            val mappingDir = "${project.buildDir}${File.separator}$mappingDir"
                            File(mappingDir).takeIf { mapping ->
                                !mapping.exists()
                            }?.apply {
                                mkdirs()
                            }

                            val originalLength = apFile.length()
                            val mappingFile = File(mappingDir, RES_DEDUPLION_MAPPING)

                            // 解压ZIP
                            val unZipDir = "${apFile.parent}${File.separator}$DIR_NAME"

                            ZipFile(apFile).unZipFiles(unZipDir)

                            deleteRepeatResources(unZipDir,mappingFile,apFile,config.ignoreFileName)

                            val okLength = restore(apFile, unZipDir)
                            println("complete : reduce  ${originalLength - okLength}")

                            // 删除多余数据
                            deleteDir(File(unZipDir))
                        }
                    }
                }
            }
        }
    }

    /**
     * 删除重复资源
     */
    private fun deleteRepeatResources(unZipDir: String,mappingFile: File, apFile: File, ignoreFileName: Array<String>?){


        val fileWriter = FileWriter(mappingFile)

        // 查询重复资源
        val groupResources = ZipFile(apFile).groupsResources()

        // 获取
        val resourcesFile = File(unZipDir, RESOURCES_NAME)
        val newResouce = FileInputStream(resourcesFile).use { fileInput ->
            val resouce = ResourceFile.fromInputStream(fileInput)
            groupResources
                .asSequence()
                .filter {
                    it.value.size > 1
                }
                .filter { filter ->
                    // 过滤
                    val name = File(filter.value[0].name).name
                    ignoreFileName?.contains(name)?.let { !it } ?: true
                }
                .forEach { zipMap->

                    val zips = zipMap.value

                    val coreResources = zips[0]

                    for (index in 1 until zips.size) {

                        val repeatZipFile = zips[index]
                        fileWriter.synchronizedWriteString("${repeatZipFile.name} => ${coreResources.name}")

                        File(unZipDir, repeatZipFile.name).delete()

                        resouce
                            .chunks
                            .asSequence()
                            .filter {
                                it is ResourceTableChunk
                            }
                            .map {
                                it as ResourceTableChunk
                            }
                            .forEach { chunk ->
                                val stringPoolChunk = chunk.stringPool
                                val index = stringPoolChunk.indexOf(repeatZipFile.name)
                                if (index != -1) {
                                    stringPoolChunk.setString(index, coreResources.name)
                                }
                            }
                    }
                }

            fileWriter.close()
            resouce
        }

        resourcesFile.delete()

        FileOutputStream(resourcesFile).use {
            it.write(newResouce.toByteArray())
        }

    }

    private fun StringPoolChunk.setString(index:Int, value:String){
        try{
            val field = javaClass.getDeclaredField("strings")
            field.isAccessible = true
            val list = field.get(this) as MutableList<String>
            list[index] = value
        }catch (e:Exception){
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun FileWriter.synchronizedWriteString(string: String) {
        write("$string${System.getProperty("line.separator")}")
    }

    private fun restore(apFile: File, unZipDir: String): Long {
        /**
         * 删除原来的
         */
        apFile.delete()

        /**
         * 处理重复资源完毕,从新压缩
         */
        ZipOutputStream(apFile.outputStream()).use {
            it.zip(unZipDir, File(unZipDir))
        }
        return apFile.length()
    }

    private fun deleteDir(file: File?): Boolean {
        if (file == null || !file.exists()) {
            return false
        }
        if (file.isFile) {
            file.delete()
        } else if (file.isDirectory) {
            val files = file.listFiles()
            for (i in files.indices) {
                deleteDir(files[i])
            }
        }
        file.delete()
        return true
    }

}

