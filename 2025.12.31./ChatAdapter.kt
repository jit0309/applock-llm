package com.example.applock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val layoutUserMessage: LinearLayout = itemView.findViewById(R.id.layoutUserMessage)
        val layoutBotMessage: LinearLayout = itemView.findViewById(R.id.layoutBotMessage)
        val textViewUserMessage: TextView = itemView.findViewById(R.id.textViewUserMessage)
        val textViewBotMessage: TextView = itemView.findViewById(R.id.textViewBotMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        if (message.isUser) {
            holder.layoutUserMessage.visibility = View.VISIBLE
            holder.layoutBotMessage.visibility = View.GONE
            holder.textViewUserMessage.text = message.text
        } else {
            holder.layoutUserMessage.visibility = View.GONE
            holder.layoutBotMessage.visibility = View.VISIBLE
            holder.textViewBotMessage.text = message.text
        }
    }

    override fun getItemCount(): Int = messages.size
}