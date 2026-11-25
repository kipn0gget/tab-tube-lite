package com.example.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.data.VideoRepository
import com.example.data.model.ChannelSubscription

class ChannelsTabView(
    private val context: Context,
    private val fonts: TabTubeFonts,
    private val onAddChannel: (channelId: String, name: String, category: String) -> Unit,
    private val onRemoveChannel: (channelId: String) -> Unit,
    private val onSelectCategory: (String) -> Unit
) {
    private val listContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(20))
    }

    val rootView = ScrollView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(listContainer)
    }

    private val idInput = EditText(context).apply {
        hint = "YouTube Channel ID (bijv. UC...)"
        setHintTextColor(TabTubeColors.TEXT_SECONDARY)
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        typeface = fonts.body
        isSingleLine = true
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_ELEVATED, TabTubeColors.BORDER_SUBTLE, 1, 8))
    }

    private val nameInput = EditText(context).apply {
        hint = "Kanaalnaam"
        setHintTextColor(TabTubeColors.TEXT_SECONDARY)
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        typeface = fonts.body
        isSingleLine = true
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_ELEVATED, TabTubeColors.BORDER_SUBTLE, 1, 8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private val catInput = EditText(context).apply {
        setText("Tech & Retro")
        hint = "Categorie"
        setHintTextColor(TabTubeColors.TEXT_SECONDARY)
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        typeface = fonts.body
        isSingleLine = true
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_ELEVATED, TabTubeColors.BORDER_SUBTLE, 1, 8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(context.dp(8), 0, 0, 0)
        }
    }

    fun render(
        subscriptions: List<ChannelSubscription>,
        settings: com.example.data.model.LegacyTuningSettings = com.example.data.model.LegacyTuningSettings()
    ) {
        val strings = AppStrings.of(settings.appLanguage)
        idInput.hint = strings.chHintId
        nameInput.hint = strings.chHintName
        catInput.hint = strings.chHintCategory
        listContainer.removeAllViews()

        val formCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 14))
        }
        formCard.addView(TextView(context).apply {
            text = strings.chAddTitle
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
        })
        formCard.addView(TextView(context).apply {
            text = strings.chAddSubtitle
            typeface = fonts.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(TabTubeColors.TEXT_SECONDARY)
            setPadding(0, context.dp(4), 0, context.dp(10))
        })

        formCard.addView(idInput)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(8), 0, context.dp(10))
        }
        row.addView(nameInput)
        row.addView(catInput)
        formCard.addView(row)

        formCard.addView(Button(context).apply {
            text = strings.chBtnSave
            typeface = fonts.heading
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SIGNAL_RED, TabTubeColors.SIGNAL_RED, 0, 8))
            setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
            setOnClickListener {
                val cid = idInput.text.toString().trim()
                if (cid.isNotEmpty()) {
                    onAddChannel(cid, nameInput.text.toString().trim(), catInput.text.toString().trim())
                    idInput.setText("")
                    nameInput.setText("")
                }
            }
        })
        listContainer.addView(formCard)

        val existingIds = subscriptions.map { it.channelId }.toSet()
        val missing = VideoRepository.DEFAULT_CHANNELS.filter { it.channelId !in existingIds }
        if (missing.isNotEmpty()) {
            listContainer.addView(TextView(context).apply {
                text = strings.chRestoreDefaults
                typeface = fonts.body
                setTextColor(TabTubeColors.TEXT_SECONDARY)
                setPadding(0, context.dp(14), 0, context.dp(6))
            })
            val suggestRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in missing) {
                suggestRow.addView(Button(context).apply {
                    text = "+ ${ch.name}"
                    setTextColor(TabTubeColors.PHOSPHOR_TEAL)
                    setCompatBackground(context.pillButtonDrawable(false))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        context.dp(48)
                    ).apply { setMargins(0, 0, context.dp(8), 0) }
                    setPadding(context.dp(12), 0, context.dp(12), 0)
                    setOnClickListener { onAddChannel(ch.channelId, ch.name, ch.category) }
                })
            }
            listContainer.addView(HorizontalScrollView(context).apply { addView(suggestRow) })
        }

        listContainer.addView(TextView(context).apply {
            text = String.format(strings.chLocalSubsFmt, subscriptions.size)
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setPadding(0, context.dp(14), 0, context.dp(8))
        })

        for (sub in subscriptions) {
            val itemRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(14), context.dp(10), context.dp(8), context.dp(10))
                setCompatBackground(context.focusableCardDrawable())
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, context.dp(8)) }
                setOnClickListener { onSelectCategory(sub.category) }
            }

            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(context).apply {
                text = sub.name
                typeface = fonts.heading
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(TabTubeColors.TEXT_PRIMARY)
            })
            info.addView(TextView(context).apply {
                text = "${strings.formatCategory(sub.category)} • ${sub.channelId}"
                typeface = fonts.mono
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(TabTubeColors.TEXT_SECONDARY)
            })

            val delBtn = Button(context).apply {
                text = "✕"
                setTextColor(TabTubeColors.SIGNAL_RED)
                setCompatBackground(context.pillButtonDrawable(false))
                layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
                contentDescription = "${strings.chRemoveCdPrefix}${sub.name}"
                setOnClickListener { onRemoveChannel(sub.channelId) }
            }

            itemRow.addView(info)
            itemRow.addView(delBtn)
            listContainer.addView(itemRow)
        }
    }
}
