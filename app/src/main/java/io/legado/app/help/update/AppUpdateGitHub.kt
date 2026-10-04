package io.legado.app.help.update

import android.os.Build
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private val checkVariant: AppVariant
        get() = AppConst.appInfo.appVariant.takeUnless { it == AppVariant.UNKNOWN }
            ?: AppVariant.OFFICIAL

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val lastReleaseUrl =
            "https://api.github.com/repos/XingYuyuop/legado-apk/releases?per_page=30"
        val res = okHttpClient.newCallResponse {
            url(lastReleaseUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        val releases = GSON.fromJsonArray<GithubRelease>(body).getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
        }
        return releases
            .filterNot { it.isPreRelease }
            .flatMap { it.gitReleaseToAppReleaseInfo() }
            .sortedByDescending { it.createdAt }
    }

    private suspend fun getBetaRelease(): List<AppReleaseInfo> {
        val res = okHttpClient.newCallResponse {
            url("https://api.github.com/repos/XingYuyuop/legado-apk/releases/tags/beta")
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException(
                "${appCtx.getString(R.string.check_beta_update_failed)}(${res.code})"
            )
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException(appCtx.getString(R.string.check_beta_update_failed))
        }
        return runCatching {
            GSON.fromJson(body, GithubRelease::class.java).gitReleaseToAppReleaseInfo()
        }.getOrElse {
            throw NoStackTraceException(
                appCtx.getString(R.string.check_beta_update_failed) + " " + it.localizedMessage
            )
        }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            selectUpdateRelease(
                releases = getLatestRelease(),
                appVariant = checkVariant,
                currentVersionName = AppConst.appInfo.versionName,
                supportedAbis = Build.SUPPORTED_ABIS.toList()
            )
                ?.let { return@async it.toUpdateInfo() }
            throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }

    fun checkBeta(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val variant = AppConst.betaUpdateVariant
                ?: throw NoStackTraceException(appCtx.getString(R.string.beta_update_unsupported))
            val releases = getBetaRelease()
            selectUpdateRelease(
                releases = releases,
                appVariant = variant,
                currentVersionName = AppConst.appInfo.versionName,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                currentVersionCode = AppConst.appInfo.versionCode
            )
                ?.let { return@async it.toUpdateInfo(isBeta = true) }
            if (releases.any {
                    it.appVariant == variant && it.isNewerThan(
                        AppConst.appInfo.versionName,
                        AppConst.appInfo.versionCode
                    )
                }) {
                throw NoStackTraceException(appCtx.getString(R.string.beta_update_no_compatible_apk))
            }
            throw NoStackTraceException(appCtx.getString(R.string.latest_beta_version))
        }.timeout(10000)
    }
}

internal fun AppReleaseInfo.toUpdateInfo(isBeta: Boolean = false): AppUpdate.UpdateInfo {
    // 默认使用GitHub原始地址下载；镜像列表在更新对话框内展示并测速，可自由选择
    val primaryUrl = downloadUrl
    val mirrors = if (isBeta) {
        emptyList()
    } else {
        updateMirrorPrefixes().map { it + downloadUrl }.distinct()
    }
    return AppUpdate.UpdateInfo(
        tagName = versionName,
        updateLog = note,
        downloadUrl = primaryUrl,
        fileName = name,
        backupDownloadUrl = null,
        mirrorDownloadUrl = null,
        alternateMirrorDownloadUrl = null,
        mirrorUrls = mirrors,
        size = size,
        createdAt = createdAt,
        isBeta = isBeta
    )
}

/**
 * 更新下载镜像前缀：用户自定义优先(关于-下载镜像编辑)，留空使用默认列表
 */
internal fun updateMirrorPrefixes(): List<String> {
    return LocalConfig.updateMirrors
        ?.split("\n", ";")
        ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
        ?.map { if (it.endsWith("/")) it else "$it/" }
        ?.takeIf { it.isNotEmpty() }
        ?: AppUpdate.defaultMirrors
}
