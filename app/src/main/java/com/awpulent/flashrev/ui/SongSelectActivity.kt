package com.awpulent.flashrev.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.awpulent.flashrev.FlashRevApp
import com.awpulent.flashrev.R
import com.awpulent.flashrev.chart.Chart
import com.awpulent.flashrev.chart.Song
import com.awpulent.flashrev.demo.DemoInstaller
import com.awpulent.flashrev.game.GameActivity
import com.awpulent.flashrev.game.GameResult

class SongSelectActivity : Activity() {

    private lateinit var app: FlashRevApp
    private lateinit var adapter: SongAdapter
    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_select)
        findViewById<View>(R.id.root).padForSystemBars()
        app = FlashRevApp.from(this)
        list = findViewById(R.id.song_list)
        empty = findViewById(R.id.empty)
        adapter = SongAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showChartPicker(adapter.getItem(position)) }
        list.setOnItemLongClickListener { _, _, position, _ -> confirmDelete(adapter.getItem(position)); true }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val songs = app.library.songs
        adapter.songs = songs
        empty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showChartPicker(song: Song) {
        val labels = song.charts.map { chart -> chartLabel(song, chart) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(song.fullTitle)
            .setItems(labels) { _, which -> startActivity(GameActivity.intent(this, song, which)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chartLabel(song: Song, chart: Chart): String {
        val best = app.scores.best(song.chartKey(chart))
        val bestText = if (best == null) getString(R.string.no_score)
        else getString(R.string.best_score, "${String.format("%,d", best.rawScore)} · ${rankName(best.rank)}")
        val name = if (chart.description.isNotBlank() && chart.difficulty.name == "EDIT") chart.description else chart.difficulty.displayName
        return "${getString(R.string.difficulty_meter, name, chart.meter)}\n${getString(R.string.notes_count, chart.noteCount)} · $bestText"
    }

    private fun rankName(rank: GameResult.Rank) = when (rank) {
        GameResult.Rank.AAA -> getString(R.string.rank_aaa)
        GameResult.Rank.SDG -> getString(R.string.rank_sdg)
        GameResult.Rank.FC -> getString(R.string.rank_fc)
        GameResult.Rank.BLACKFLAG -> getString(R.string.rank_blackflag)
        GameResult.Rank.CLEAR -> getString(R.string.rank_clear)
    }

    private fun confirmDelete(song: Song) {
        if (song.id == DemoInstaller.DEMO_DIR_NAME) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_song)
            .setMessage(getString(R.string.delete_song_confirm, song.fullTitle))
            .setPositiveButton(R.string.delete) { _, _ ->
                app.library.delete(song)
                app.scores.clear("${song.id}|")
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class SongAdapter(context: Context) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        private val app = FlashRevApp.from(context)
        var songs: List<Song> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount() = songs.size
        override fun getItem(position: Int) = songs[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_song, parent, false)
            val song = getItem(position)
            view.findViewById<TextView>(R.id.title).text = song.fullTitle
            val meta = buildString {
                if (song.artist.isNotBlank()) append(song.artist).append("  ·  ")
                append(song.displayBpm).append(" BPM")
            }
            view.findViewById<TextView>(R.id.meta).text = meta
            view.findViewById<TextView>(R.id.charts).text = song.charts.joinToString("   ") { chart ->
                val best = app.scores.best(song.chartKey(chart))
                val mark = when (best?.rank) {
                    null -> ""
                    GameResult.Rank.AAA -> " ★"
                    GameResult.Rank.SDG, GameResult.Rank.FC -> " ✓"
                    else -> " ·"
                }
                "${chart.difficulty.displayName} ${chart.meter}$mark"
            }
            return view
        }
    }
}
