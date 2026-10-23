package cn.rbc.termuc;

import java.util.ArrayList;

/** Stores open-tab order; DocumentSessions owns dirty state. */
public class HeaderAdapter extends ArrayList<String> {
    private OnChangedListener listener;

    public int getCount() { return size(); }
    public String getItem(int index) { return get(index); }

    @Override
    public boolean add(String item) {
        boolean added = super.add(item);
        if (added && listener != null) listener.onAdd(item);
        return added;
    }

    @Override
    public boolean remove(Object item) {
        int position = indexOf(item);
        if (position < 0) return false;
        boolean removed = super.remove(item);
        if (removed) {
            if (listener != null) listener.onRemove((String)item);
        }
        return removed;
    }

    @Override
    public void clear() {
        super.clear();
        if (listener != null) listener.onClear();
    }

    public void setOnChangedListener(OnChangedListener value) { listener = value; }

    interface OnChangedListener {
        void onAdd(String item);
        void onRemove(String item);
        void onClear();
    }
}
