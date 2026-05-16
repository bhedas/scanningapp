package com.example.bleapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ParserSetupActivity : AppCompatActivity() {

    // ── View holders for dynamic form elements ────────────────────────────────

    private data class FilterRowHolder(
        val view: android.view.View,
        val byteIndexEdit: EditText,
        val byteValueEdit: EditText
    )

    private data class RuleRowHolder(
        val view: android.view.View,
        val labelEdit: EditText,
        val byteStartEdit: EditText,
        val byteEndEdit: EditText,
        val formatSpinner: Spinner,
        val multiplierEdit: EditText,
        val unitEdit: EditText
    )

    private data class FrameHolder(
        val cardView: android.view.View,
        val nameEdit: EditText,
        val filtersContainer: LinearLayout,
        val rulesContainer: LinearLayout,
        val filters: MutableList<FilterRowHolder> = mutableListOf(),
        val rules: MutableList<RuleRowHolder> = mutableListOf()
    )

    private val frameHolders = mutableListOf<FrameHolder>()
    private lateinit var framesContainer: LinearLayout
    private lateinit var deviceAddress: String
    private lateinit var deviceName: String

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parser_setup)

        deviceAddress = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ADDRESS) ?: ""
        deviceName    = intent.getStringExtra(MainActivity.EXTRA_DEVICE_NAME) ?: "Device"

        supportActionBar?.title = "Parser Setup"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        framesContainer = findViewById(R.id.framesContainer)

        findViewById<MaterialButton>(R.id.addFrameBtn).setOnClickListener { addFrame() }
        findViewById<MaterialButton>(R.id.startParsingBtn).setOnClickListener { buildAndStart() }

        // Start with one frame by default
        addFrame()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    // ── Frame management ──────────────────────────────────────────────────────

    private fun addFrame() {
        val cardView = LayoutInflater.from(this)
            .inflate(R.layout.item_frame_setup_card, framesContainer, false)

        val nameEdit        = cardView.findViewById<EditText>(R.id.frameNameEdit)
        val filtersContainer = cardView.findViewById<LinearLayout>(R.id.filtersContainer)
        val rulesContainer  = cardView.findViewById<LinearLayout>(R.id.rulesContainer)
        val addFilterBtn    = cardView.findViewById<MaterialButton>(R.id.addFilterBtn)
        val addRuleBtn      = cardView.findViewById<MaterialButton>(R.id.addRuleBtn)
        val removeFrameBtn  = cardView.findViewById<MaterialButton>(R.id.removeFrameBtn)

        val frameIndex = frameHolders.size + 1
        nameEdit.hint = "Frame $frameIndex"

        val holder = FrameHolder(cardView, nameEdit, filtersContainer, rulesContainer)
        frameHolders.add(holder)
        framesContainer.addView(cardView)

        // Default: one filter + one rule
        addFilterRow(holder)
        addRuleRow(holder)

        addFilterBtn.setOnClickListener   { addFilterRow(holder) }
        addRuleBtn.setOnClickListener     { addRuleRow(holder) }
        removeFrameBtn.setOnClickListener {
            framesContainer.removeView(cardView)
            frameHolders.remove(holder)
        }
    }

    // ── Filter row ────────────────────────────────────────────────────────────

    private fun addFilterRow(fh: FrameHolder) {
        val rowView = LayoutInflater.from(this)
            .inflate(R.layout.item_filter_row, fh.filtersContainer, false)

        val byteIndexEdit = rowView.findViewById<EditText>(R.id.filterByteIndex)
        val byteValueEdit = rowView.findViewById<EditText>(R.id.filterByteValue)
        val removeBtn     = rowView.findViewById<ImageButton>(R.id.removeFilterBtn)

        val rowHolder = FilterRowHolder(rowView, byteIndexEdit, byteValueEdit)
        fh.filters.add(rowHolder)
        fh.filtersContainer.addView(rowView)

        removeBtn.setOnClickListener {
            fh.filtersContainer.removeView(rowView)
            fh.filters.remove(rowHolder)
        }
    }

    // ── Rule row ──────────────────────────────────────────────────────────────

    private fun addRuleRow(fh: FrameHolder) {
        val rowView = LayoutInflater.from(this)
            .inflate(R.layout.item_rule_row, fh.rulesContainer, false)

        val labelEdit      = rowView.findViewById<EditText>(R.id.ruleLabel)
        val byteStartEdit  = rowView.findViewById<EditText>(R.id.ruleByteStart)
        val byteEndEdit    = rowView.findViewById<EditText>(R.id.ruleByteEnd)
        val formatSpinner  = rowView.findViewById<Spinner>(R.id.formatSpinner)
        val multiplierEdit = rowView.findViewById<EditText>(R.id.ruleMultiplier)
        val unitEdit       = rowView.findViewById<EditText>(R.id.ruleUnit)
        val removeBtn      = rowView.findViewById<ImageButton>(R.id.removeRuleBtn)
        val ruleNumber     = rowView.findViewById<TextView>(R.id.ruleNumber)

        // Format spinner
        val formats = DataFormat.values().map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formats)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        formatSpinner.adapter = adapter

        val ruleIndex = fh.rules.size + 1
        ruleNumber.text = "Rule $ruleIndex"

        val rowHolder = RuleRowHolder(
            rowView, labelEdit, byteStartEdit, byteEndEdit,
            formatSpinner, multiplierEdit, unitEdit
        )
        fh.rules.add(rowHolder)
        fh.rulesContainer.addView(rowView)

        removeBtn.setOnClickListener {
            fh.rulesContainer.removeView(rowView)
            fh.rules.remove(rowHolder)
        }
    }

    // ── Build config & launch ─────────────────────────────────────────────────

    private fun buildAndStart() {
        if (frameHolders.isEmpty()) {
            toast("Add at least one frame definition"); return
        }

        // Data source
        val dataSource = when (findViewById<RadioGroup>(R.id.dataSourceGroup).checkedRadioButtonId) {
            R.id.radioManufacturer -> DataSource.MANUFACTURER
            R.id.radioRaw         -> DataSource.RAW
            R.id.radioService     -> DataSource.SERVICE
            else                  -> DataSource.MANUFACTURER
        }

        val frames = mutableListOf<FrameDefinition>()

        for (fh in frameHolders) {
            val name = fh.nameEdit.text?.toString()?.trim()
                .takeIf { !it.isNullOrBlank() } ?: "Frame ${frames.size + 1}"

            // Parse filters
            if (fh.filters.isEmpty()) { toast("'$name' needs at least one filter"); return }
            val filters = mutableListOf<FilterRule>()
            for (fr in fh.filters) {
                val idxStr = fr.byteIndexEdit.text?.toString()?.trim()
                val valStr = fr.byteValueEdit.text?.toString()?.trim()
                    ?.removePrefix("0x")?.removePrefix("0X")

                val idx = idxStr?.toIntOrNull()
                val v   = valStr?.toIntOrNull(16)?.toByte()

                if (idx == null || v == null) {
                    toast("'$name': fill all filter fields with valid values"); return
                }
                filters.add(FilterRule(idx, v))
            }

            // Parse rules
            if (fh.rules.isEmpty()) { toast("'$name' needs at least one rule"); return }
            val rules = mutableListOf<ParseRule>()
            for (rr in fh.rules) {
                val label  = rr.labelEdit.text?.toString()?.trim() ?: ""
                val start  = rr.byteStartEdit.text?.toString()?.toIntOrNull()
                val end    = rr.byteEndEdit.text?.toString()?.toIntOrNull()
                val mult   = rr.multiplierEdit.text?.toString()?.toDoubleOrNull() ?: 1.0
                val unit   = rr.unitEdit.text?.toString()?.trim() ?: ""
                val format = DataFormat.values()[rr.formatSpinner.selectedItemPosition]

                if (start == null || end == null) {
                    toast("'$name': byte range must be valid numbers"); return
                }
                if (start > end) {
                    toast("'$name': start byte must be ≤ end byte"); return
                }
                rules.add(ParseRule(label.ifBlank { "Field ${rules.size + 1}" }, start, end, format, mult, unit))
            }

            frames.add(FrameDefinition(name, filters, rules))
        }

        ParserConfigHolder.config = ParserConfig(dataSource, frames)

        startActivity(Intent(this, ParsedLiveActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(MainActivity.EXTRA_DEVICE_NAME, deviceName)
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
