package com.dbpg.duihuazhushou.assets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.character.PersonaStore
import java.io.File

/**
 * 资产页「角色」tab 里的「我的角色」分区。
 *
 * 为什么单独一栏而不是混进角色卡库：
 *   - 角色卡 = AI 要演的人，一个会话绑一张；
 *   - 用户卡 = "你"是谁，全局一张，写进 `{{user}}` / 用户人设块。
 * 混在一起会让人以为用户卡也能"用它开聊"。
 *
 * 编辑走弹窗而不是新页面：字段只有名字 + 一句设定 + 头像，
 * 为它单开一个 Activity 不划算。
 */
object PersonaSection {

    /** 选头像的 requestCode，由 [AssetsHomeActivity.onActivityResult] 转发回来。 */
    const val REQ_AVATAR = 4101

    /** 选完头像要写回哪个用户角色。Activity 被回收也只会丢这一次选择，不影响数据。 */
    private var pendingId = ""

    fun render(activity: Activity, box: LinearLayout) {
        val items = PersonaStore.list(activity)
        val active = PersonaStore.active(activity)

        // ---- 分区标题 ----
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 18), 0, dp(activity, 6))
        }
        head.addView(TextView(activity).apply {
            text = "我的角色"
            setTextColor(0xFF1A2230.toInt())
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(activity).apply {
            text = "＋ 新建"
            setTextColor(0xFF2F66D8.toInt())
            textSize = 13f
            setPadding(dp(activity, 8), dp(activity, 4), 0, dp(activity, 4))
            setOnClickListener { edit(activity, null) }
        })
        box.addView(head, rowLp())

        box.addView(TextView(activity).apply {
            text = "群聊和单人聊天都用它：名字进 {{user}}，设定只在需要时注入"
            setTextColor(0xFF9AA4B2.toInt())
            textSize = 11f
            setPadding(0, 0, 0, dp(activity, 8))
        }, rowLp())

        if (items.isEmpty()) {
            box.addView(TextView(activity).apply {
                text = "还没有用户角色。建一个，AI 才知道「你」是谁。"
                setTextColor(0xFF5A6B85.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 18), 0, dp(activity, 18))
                background = activity.getDrawable(R.drawable.bg_card)
                setOnClickListener { edit(activity, null) }
            }, rowLp())
            return
        }

        for (p in items) {
            box.addView(row(activity, p, active?.id == p.id), rowLp(bottomDp = 8))
        }
    }

    // ------------------------------------------------------------ 行

    private fun row(activity: Activity, p: PersonaStore.Persona, isActive: Boolean): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = activity.getDrawable(R.drawable.bg_card)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
        }
        card.addView(avatarView(activity, p, 42))
        val mid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), 0, dp(activity, 8), 0)
        }
        mid.addView(TextView(activity).apply {
            text = p.name
            setTextColor(0xFF1A2230.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        mid.addView(TextView(activity).apply {
            text = p.description.ifBlank { "没写设定" }
            setTextColor(0xFF66707F.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(activity, 3), 0, 0)
        })
        card.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(TextView(activity).apply {
            text = if (isActive) "使用中" else "使用"
            setTextColor(if (isActive) 0xFF2F66D8.toInt() else 0xFF5A6B85.toInt())
            textSize = 12f
            setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5))
            background = activity.getDrawable(
                if (isActive) R.drawable.bg_pill_accent else R.drawable.bg_pill_soft,
            )
            setOnClickListener {
                if (!isActive) {
                    PersonaStore.setActive(activity, p.id)
                    refresh(activity)
                }
            }
        })

        card.setOnClickListener { menu(activity, p) }
        card.setOnLongClickListener { menu(activity, p); true }
        return card
    }

    private fun menu(activity: Activity, p: PersonaStore.Persona) {
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setItems(arrayOf("编辑", "换头像", "删除")) { _, which ->
                when (which) {
                    0 -> edit(activity, p)
                    1 -> {
                        pendingId = p.id
                        activity.startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "image/*"
                            },
                            REQ_AVATAR,
                        )
                    }
                    2 -> AlertDialog.Builder(activity)
                        .setTitle("删除用户角色")
                        .setMessage("「${p.name}」会被删除，聊天记录不受影响。")
                        .setPositiveButton("删除") { _, _ ->
                            PersonaStore.delete(activity, p.id)
                            refresh(activity)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选头像回来：压缩落盘并刷新。 */
    fun handleAvatarResult(activity: Activity, uri: Uri) {
        val id = pendingId
        pendingId = ""
        if (id.isEmpty()) return
        val name = PersonaStore.saveAvatar(activity, id, uri)
        if (name.isEmpty()) {
            android.widget.Toast.makeText(activity, "头像读取失败", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val p = PersonaStore.get(activity, id) ?: return
        PersonaStore.upsert(activity, p.copy(avatar = name))
        refresh(activity)
    }

    // ------------------------------------------------------------ 编辑弹窗

    private fun edit(activity: Activity, p: PersonaStore.Persona?) {
        val pad = dp(activity, 20)
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(activity, 8), pad, 0)
        }
        val name = EditText(activity).apply {
            hint = "名字，例如：林渡"
            setText(p?.name.orEmpty())
            textSize = 15f
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9))
        }
        wrap.addView(name, rowLp())
        val desc = EditText(activity).apply {
            hint = "设定：身份、外貌、性格、和角色们的关系…"
            setText(p?.description.orEmpty())
            textSize = 14f
            gravity = Gravity.TOP
            minLines = 4
            maxLines = 8
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9))
        }
        wrap.addView(desc, rowLp(topDp = 10))

        AlertDialog.Builder(activity)
            .setTitle(if (p == null) "新建用户角色" else "编辑用户角色")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val n = name.text?.toString().orEmpty().trim()
                if (n.isEmpty()) {
                    android.widget.Toast.makeText(activity, "名字不能为空", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                PersonaStore.upsert(
                    activity,
                    PersonaStore.Persona(
                        id = p?.id.orEmpty(),
                        name = n,
                        description = desc.text?.toString().orEmpty().trim(),
                        avatar = p?.avatar.orEmpty(),
                    ),
                )
                refresh(activity)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 小工具

    private fun refresh(activity: Activity) {
        (activity as? AssetsHomeActivity)?.let {
            it.reloadKeepingScrollPublic()
        }
    }

    private fun avatarView(activity: Activity, p: PersonaStore.Persona, sizeDp: Int): ImageView {
        val size = dp(activity, sizeDp)
        return ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = activity.getDrawable(R.drawable.avatar_bg)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            val f = PersonaStore.avatarFile(activity, p)
            if (f != null) {
                val bmp = decodeSmall(f, size)
                if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.ic_avatar_placeholder)
            } else {
                setImageResource(R.drawable.ic_avatar_placeholder)
            }
        }
    }

    private fun decodeSmall(f: File, wantPx: Int): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > wantPx * 2 || opts.outHeight / sample > wantPx * 2) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun rowLp(topDp: Int = 0, bottomDp: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = topDp
        bottomMargin = bottomDp
    }

    private fun dp(activity: Activity, v: Int): Int =
        Math.round(v * activity.resources.displayMetrics.density)
}
