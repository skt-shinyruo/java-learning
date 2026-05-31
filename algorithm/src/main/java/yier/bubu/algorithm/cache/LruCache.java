package yier.bubu.algorithm.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LruCache<K, V> {

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> before;
        Node<K, V> after;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> entries;
    private Node<K, V> head;
    private Node<K, V> tail;

    public LruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new HashMap<K, Node<K, V>>();
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean containsKey(K key) {
        return entries.containsKey(key);
    }

    public V get(K key) {
        Node<K, V> node = entries.get(key);
        if (node == null) {
            return null;
        }

        moveToTail(node);
        return node.value;
    }

    public V put(K key, V value) {
        Node<K, V> node = entries.get(key);
        if (node != null) {
            V oldValue = node.value;
            node.value = value;
            moveToTail(node);
            return oldValue;
        }

        Node<K, V> newNode = new Node<K, V>(key, value);
        entries.put(key, newNode);
        linkLast(newNode);

        if (entries.size() > capacity) {
            evictHead();
        }
        return null;
    }

    public V remove(K key) {
        Node<K, V> node = entries.remove(key);
        if (node == null) {
            return null;
        }

        unlink(node);
        return node.value;
    }

    public void clear() {
        entries.clear();
        head = null;
        tail = null;
    }

    public List<K> keysFromLeastToMostRecentlyUsed() {
        List<K> keys = new ArrayList<K>(entries.size());
        Node<K, V> node = head;
        while (node != null) {
            keys.add(node.key);
            node = node.after;
        }
        return keys;
    }

    private void evictHead() {
        Node<K, V> eldest = head;
        if (eldest == null) {
            return;
        }

        entries.remove(eldest.key);
        unlink(eldest);
    }

    private void moveToTail(Node<K, V> node) {
        if (node == tail) {
            return;
        }

        unlink(node);
        linkLast(node);
    }

    private void linkLast(Node<K, V> node) {
        Node<K, V> oldTail = tail;
        tail = node;

        if (oldTail == null) {
            head = node;
        } else {
            node.before = oldTail;
            oldTail.after = node;
        }
    }

    private void unlink(Node<K, V> node) {
        Node<K, V> before = node.before;
        Node<K, V> after = node.after;

        if (before == null) {
            head = after;
        } else {
            before.after = after;
        }

        if (after == null) {
            tail = before;
        } else {
            after.before = before;
        }

        node.before = null;
        node.after = null;
    }
}
