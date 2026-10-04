package io.github.a13e300.ksuwebui

import android.app.Activity
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.topjohnwu.superuser.CallbackList
import io.github.a13e300.ksuwebui.databinding.SheetActionBinding

/** Runs a module's action.sh as root and streams the output into a bottom sheet. */
object ActionRunner {
    fun run(activity: Activity, module: Module) {
        val binding = SheetActionBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setCancelable(false)
        binding.title.text = module.name
        binding.status.setText(R.string.action_running)
        binding.close.setOnClickListener { dialog.dismiss() }

        val out = StringBuilder()
        val sink = object : CallbackList<String>() {
            override fun onAddElement(s: String) {
                out.append(s).append('\n')
                binding.output.text = out
                binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        dialog.show()

        val dir = shellQuote(module.dir)
        val script = """
            cd $dir || exit 1
            export ASH_STANDALONE=1 MODPATH=$dir
            [ -d /data/adb/ksu ] && export KSU=true
            [ -d /data/adb/ap ] && export APATCH=true
            for BB in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
              [ -x "${'$'}BB" ] && exec "${'$'}BB" sh ./action.sh
            done
            exec sh ./action.sh
        """.trimIndent()

        App.executor.submit {
            val code = runCatching {
                withNewRootShell(true) {
                    newJob().add(script).to(sink, sink).exec().code
                }
            }.getOrElse {
                activity.runOnUiThread { sink.add(it.toString()) }
                -1
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                binding.progress.isVisible = false
                binding.status.text = if (code == 0) activity.getString(R.string.action_done)
                else activity.getString(R.string.action_failed, code)
                binding.close.isEnabled = true
                dialog.setCancelable(true)
            }
        }
    }
}
