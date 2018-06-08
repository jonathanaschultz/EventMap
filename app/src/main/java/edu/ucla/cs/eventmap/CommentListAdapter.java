package edu.ucla.cs.eventmap;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class CommentListAdapter extends ArrayAdapter<Comment> {
    private List<Comment> items;
    private int layoutResourceId;
    private Context context;
    private boolean eventOwner; //If the event owner is invoking this adapter, enable and show the button to pin a given comment
    public CommentListAdapter(Context context, int layoutResourceId, List<Comment> items, boolean eventOwner) {
        super(context, layoutResourceId, items);
        this.context = context;
        this.layoutResourceId = layoutResourceId;
        this.items = items;
        this.eventOwner = eventOwner;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        CommentHolder holder = null;
        LayoutInflater inflater = ((Activity) context).getLayoutInflater();
        row = inflater.inflate(layoutResourceId, parent, false);
        holder = new CommentHolder();
        holder.comment = items.get(position);
        holder.commentText = (TextView)row.findViewById(R.id.commentText);
        holder.timeText = (TextView)row.findViewById(R.id.timeText);
        if (holder.comment.pin == 1) {
            holder.commentText.setTypeface(null, Typeface.BOLD);
        }
        else {
            holder.commentText.setTypeface(null, Typeface.NORMAL);
        }
        if (eventOwner) {
            holder.pinCommentButton = (Button)row.findViewById(R.id.pinButton);
            if (holder.comment.pin == 1) {
                holder.pinCommentButton.setText("Unpin");
            }
            else {
                holder.pinCommentButton.setText("Pin");
            }
            holder.pinCommentButton.setVisibility(View.VISIBLE);
            holder.pinCommentButton.setEnabled(true);
            holder.pinCommentButton.setTag(holder.comment);
        }
        if (holder.comment.owner.equals(MapsActivity.uid) || eventOwner) { //Only enable the button if the owner of the comment is the one who added it or the event creator
            holder.deleteCommentButton = (Button)row.findViewById(R.id.deleteButton);
            holder.deleteCommentButton.setVisibility(View.VISIBLE);
            holder.deleteCommentButton.setEnabled(true);
            holder.deleteCommentButton.setTag(holder.comment);
        }
        row.setTag(holder);
        holder.commentText.setText(holder.comment.comment);
        holder.timeText.setText(holder.comment.username + " @ " + holder.comment.time);
        return row;
    }


    public static class CommentHolder {
        Comment comment;
        TextView commentText;
        TextView timeText;
        Button deleteCommentButton;
        Button pinCommentButton;
    }
}
