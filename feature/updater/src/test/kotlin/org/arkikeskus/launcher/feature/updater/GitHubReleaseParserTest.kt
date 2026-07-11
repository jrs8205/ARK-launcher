package org.arkikeskus.launcher.feature.updater

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubReleaseParserTest {
    private val sample = """
      {"tag_name":"v0.3.0","body":"- New thing\n- Fix",
       "assets":[
         {"name":"mapping.txt","browser_download_url":"https://x/mapping.txt","size":10},
         {"name":"arkikeskus-launcher-0.3.0.apk","browser_download_url":"https://x/app.apk","size":4560052}
       ]}
    """.trimIndent()

    @Test fun parses_tag_notes_and_apk_asset() {
        val r = GitHubReleaseParser.parse(sample)!!
        assertThat(r.tag).isEqualTo("v0.3.0")
        assertThat(r.notes).contains("New thing")
        assertThat(r.apkUrl).isEqualTo("https://x/app.apk")
        assertThat(r.sizeBytes).isEqualTo(4560052L)
    }

    @Test fun returns_null_when_no_apk_asset() {
        val noApk = """{"tag_name":"v0.3.0","body":"x","assets":[{"name":"notes.txt","browser_download_url":"u","size":1}]}"""
        assertThat(GitHubReleaseParser.parse(noApk)).isNull()
    }

    @Test fun prefers_the_named_launcher_apk_over_other_apks() {
        val json = """
          {"tag_name":"v0.8.0","body":"x","assets":[
            {"name":"app-debug.apk","browser_download_url":"https://x/debug.apk","size":1},
            {"name":"ARK-launcher-0.8.0.apk","browser_download_url":"https://x/rel.apk","size":2}
          ]}
        """.trimIndent()
        assertThat(GitHubReleaseParser.parse(json)!!.apkUrl).isEqualTo("https://x/rel.apk")
    }

    @Test fun parses_the_asset_sha256_digest() {
        val sha = "a".repeat(64)
        val json = """
          {"tag_name":"v0.8.0","body":"x","assets":[
            {"name":"ARK-launcher-0.8.0.apk","browser_download_url":"https://x/a.apk","size":100,"digest":"sha256:$sha"}
          ]}
        """.trimIndent()
        assertThat(GitHubReleaseParser.parse(json)!!.sha256).isEqualTo(sha)
    }

    @Test fun missing_or_malformed_digest_gives_null_sha256() {
        val json = """
          {"tag_name":"v0.8.0","body":"x","assets":[
            {"name":"ARK-launcher-0.8.0.apk","browser_download_url":"https://x/a.apk","size":100,"digest":"md5:zz"}
          ]}
        """.trimIndent()
        assertThat(GitHubReleaseParser.parse(json)!!.sha256).isNull()
        assertThat(GitHubReleaseParser.parse(sample)!!.sha256).isNull()
    }

    @Test fun ignores_a_debug_apk_when_no_named_apk_present() {
        val json = """
          {"tag_name":"v0.8.0","body":"x","assets":[
            {"name":"app-debug.apk","browser_download_url":"https://x/debug.apk","size":1}
          ]}
        """.trimIndent()
        assertThat(GitHubReleaseParser.parse(json)).isNull()
    }
}
