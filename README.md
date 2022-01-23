# 组件化项目包体积优化（资源去重）

### 1.简介

组件化项目资源都是以组件名开头比如home_bg_main.png。因此经常会出现同样的图片在多个组件存放，名字不同。是包大小快速膨胀。因此对于组件化项目我们得想办法去除重复的图片，避免安装包大小膨胀。

### 2.源码地址

[GitHub](https://github.com/cnoke/ResDeduplication)

### 3.本插件使用

#### 添加依赖

[![Download](https://maven-badges.herokuapp.com/maven-central/io.github.cnoke/resplugin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.cnoke/resplugin)

项目build.gradle中添加如下引用

```groovy
buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath "com.android.tools.build:gradle:4.0.1"//gradle版本号大于4.0.1
        classpath 'io.github.cnoke:resplugin:?'//版本用上面maven centeral显示版本
    }
}
```

app  build.gradle中添加如下引用

```groovy
plugins {
    id 'com.android.application'
    id 'resplugin'//添加该插件
}
```

通过如上步骤就能开启资源文件去重，避免组化项目资源文件重复

如果需要控制插件的开启关闭，忽略某些文件可在app build.gradle增加如下配置

```groovy
//插件配置，默认情况无需配置
ResDeduplication{
    enable = true //是否打开插件
    enableWhenDebug = true //debug模式是否使用插件
    ignoreFileName = ["111.png","222.jpg"]//忽略文件列表
}
```

#### 4.原理

app打包是gradle通过一个一个task完成。processDebugResources是task使用aapt 打包资源。那么在processDebugResources执行完成之后，我们可以插入自己的task做如下操作：

1. 解压文件
2. 通过对比文件 **CRC-32**判断文件是否相同。
3. 如果相同，修改**resources.arsc**把重复的资源都重定向到一个资源上
4. 保留引用资源，删除其他重复资源
5. 重新压缩文件

#### 5.主要代码

```kotlin
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
						// 重复的资源
                        val repeatZipFile = zips[index]
                        fileWriter.synchronizedWriteString("${repeatZipFile.name} => ${coreResources.name}")
						// 删除解压的路径的重复文件
                        File(unZipDir, repeatZipFile.name).delete()
						// 将这些重复的资源都重定向到同一个文件上
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
```

