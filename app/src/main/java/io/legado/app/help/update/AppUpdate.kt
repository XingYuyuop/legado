package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    /**
     * 默认下载镜像前缀(每项拼在下载链接前面)，本地直连实测延迟排序
     * 公共镜像失效频繁，用户可在 关于-下载镜像 里编辑替换
     */
    val defaultMirrors = listOf(
        "https://mirror.houlang.cloud/",
        "https://gh.ddlc.top/",
        "https://cors.isteed.cc/",
        "https://github.akams.cn/",
        "https://gh-proxy.com/",
        "https://gitproxy.click/",
        "https://ghproxy.net/",
        "https://ghproxy.homeboyc.cn/",
        "https://gh.llkk.cc/",
        "https://ghfast.top/"
    )

    val gitHubUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }

    fun checkBeta(scope: CoroutineScope): Coroutine<UpdateInfo> =
        AppUpdateGitHub.checkBeta(scope)

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val backupDownloadUrl: String? = null,
        val mirrorDownloadUrl: String? = null,
        val alternateMirrorDownloadUrl: String? = null,
        val mirrorUrls: List<String> = emptyList(),
        val size: Long = 0L,
        val createdAt: Long = 0L,
        val isBeta: Boolean = false
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

}
