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
    private final Node<K, V> dummyHead;

    public LruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new HashMap<K, Node<K, V>>();
        this.dummyHead = new Node<K, V>(null, null);
        this.dummyHead.before = this.dummyHead;
        this.dummyHead.after = this.dummyHead;
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

        moveToHead(node);
        return node.value;
    }

    public V put(K key, V value) {
        Node<K, V> node = entries.get(key);
        if (node != null) {
            V oldValue = node.value;
            node.value = value;
            moveToHead(node);
            return oldValue;
        }

        Node<K, V> newNode = new Node<K, V>(key, value);
        entries.put(key, newNode);
        linkFirst(newNode);

        if (entries.size() > capacity) {
            evictTail();
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
        dummyHead.before = dummyHead;
        dummyHead.after = dummyHead;
    }

    public List<K> keysFromLeastToMostRecentlyUsed() {
        List<K> keys = new ArrayList<K>(entries.size());
        Node<K, V> node = dummyHead.before;
        while (node != dummyHead) {
            keys.add(node.key);
            node = node.before;
        }
        return keys;
    }

    public List<K> keysFromMostToLeastRecentlyUsed() {
        List<K> keys = new ArrayList<K>(entries.size());
        Node<K, V> node = dummyHead.after;
        while (node != dummyHead) {
            keys.add(node.key);
            node = node.after;
        }
        return keys;
    }

    private void evictTail() {
        Node<K, V> eldest = dummyHead.before;
        if (eldest == dummyHead) {
            return;
        }

        entries.remove(eldest.key);
        unlink(eldest);
    }

    private void moveToHead(Node<K, V> node) {
        if (node.before == dummyHead) {
            return;
        }

        unlink(node);
        linkFirst(node);
    }

    private void linkFirst(Node<K, V> node) {
        Node<K, V> oldHead = dummyHead.after;
        node.before = dummyHead;
        node.after = oldHead;
        dummyHead.after = node;
        oldHead.before = node;
    }

    private void unlink(Node<K, V> node) {
        node.before.after = node.after;
        node.after.before = node.before;

        node.before = null;
        node.after = null;
    }
}
