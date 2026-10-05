package com.studybook.reader

import android.app.Activity
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** Bottom sheet listing the Chinese characters of a page; the chosen ones are sent to writing practice. */
object PracticePicker {

    fun show(
        activity: Activity,
        characters: List<String>,
        preselected: Set<String>,
        recognised: Boolean = false,
        onPractise: (List<String>) -> Unit,
    ) {
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(activity).apply {
            setText(R.string.practise_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val hint = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
        }
        root.addView(title)
        root.addView(hint)

        val chips = ChipGroup(activity)
        val practise = MaterialButton(activity)
        val quiz = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun chosen() = (0 until chips.childCount).map { chips.getChildAt(it) as Chip }.filter { it.isChecked }.map { it.text.toString() }
        fun refresh() {
            val n = chosen().size
            practise.isEnabled = n > 0
            practise.text = activity.getString(R.string.practise_button, n)
            quiz.isEnabled = n > 0
            quiz.text = activity.getString(R.string.quiz_button, n)
        }

        if (characters.isEmpty()) {
            hint.setText(R.string.practise_none)
        } else {
            hint.setText(if (recognised) R.string.practise_hint_recognised else R.string.practise_hint)
            for (ch in characters) {
                chips.addView(Chip(activity).apply {
                    text = ch
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    isCheckable = true
                    isChecked = ch in preselected
                    chipMinHeight = 56 * dp
                    setOnCheckedChangeListener { _, _ -> refresh() }
                })
            }
            root.addView(ScrollView(activity).apply {
                addView(chips)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (16 * dp).toInt(), 0, 0)
            }
            val all = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setText(R.string.select_all)
                setOnClickListener {
                    val everyChecked = (0 until chips.childCount).all { (chips.getChildAt(it) as Chip).isChecked }
                    for (i in 0 until chips.childCount) (chips.getChildAt(i) as Chip).isChecked = !everyChecked
                }
            }
            practise.setOnClickListener {
                dialog.dismiss()
                onPractise(chosen())
            }
            quiz.setOnClickListener {
                val (added, total) = QuizStash.add(activity, chosen())
                Toast.makeText(activity,
                    if (added == 0) activity.getString(R.string.quiz_already) else activity.getString(R.string.quiz_added, added, total),
                    Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            buttons.addView(all)
            buttons.addView(android.view.View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            buttons.addView(quiz)
            buttons.addView(practise, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = (8 * dp).toInt() })
            root.addView(buttons)
            refresh()
        }

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
