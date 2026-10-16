package com.leadzam.clicktocall

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class RequestAdapter(
    private val onCall: (CallRequest) -> Unit,
    private val onDismiss: (CallRequest) -> Unit,
) : ListAdapter<CallRequest, RequestAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.itemTitle)
        val number: TextView = view.findViewById(R.id.itemNumber)
        val meta: TextView = view.findViewById(R.id.itemMeta)
        val status: TextView = view.findViewById(R.id.itemStatus)
        val call: Button = view.findViewById(R.id.itemCall)
        val dismiss: Button = view.findViewById(R.id.itemDismiss)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false)
    )

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = getItem(position)
        val ctx = h.itemView.context
        h.title.text = ctx.getString(R.string.item_title, r.displayName)
        h.number.text = r.phoneNumber
        h.meta.text = ctx.getString(
            R.string.item_meta,
            r.source,
            DateUtils.getRelativeTimeSpanString(r.receivedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        )

        val pending = r.status == RequestStatus.PENDING
        h.call.visibility = if (pending) View.VISIBLE else View.GONE
        h.dismiss.visibility = if (pending) View.VISIBLE else View.GONE
        h.status.visibility = if (pending) View.GONE else View.VISIBLE
        h.status.setText(
            if (r.status == RequestStatus.OPENED) R.string.status_opened else R.string.status_dismissed
        )
        h.call.contentDescription = ctx.getString(R.string.cd_call, r.displayName, r.phoneNumber)
        h.call.setOnClickListener { onCall(r) }
        h.dismiss.setOnClickListener { onDismiss(r) }
    }

    private object Diff : DiffUtil.ItemCallback<CallRequest>() {
        override fun areItemsTheSame(a: CallRequest, b: CallRequest) = a.requestId == b.requestId
        override fun areContentsTheSame(a: CallRequest, b: CallRequest) = a == b
    }
}
