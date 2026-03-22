Listpack specification
===

    Version 1.0, 1 Feb 2017: Intial specification.

    Version 1.1, 2 Feb 2017: Integer encoding simplified. Appendix A added.

    Version 1.2, 3 Feb 2017: Better specify the meaning of the num-elements
                             field with value of 65535. The two 12 bits
                             positive/negative integers encodings were
                             replaced by a single 13 bit signed integer.
                             Crash resistance better specified.
                             (Thanks to Oran Agra for all the hints).

    Salvatore Sanfilippo
    Yuval Inbar
    Oran Agra

以上是这份规范的版本演进记录：1.0 版给出了初始规范；1.1 版简化了整数编码，并加入附录 A；1.2 版进一步明确了 `num-elements` 在取值 65535 时的含义，把原先两个 12 bit 的正负整数编码合并为一个 13 bit 有符号整数编码，同时补充了关于 crash resistance 的说明。作者为 Salvatore Sanfilippo、Yuval Inbar 和 Oran Agra。

Since the early stage of Redis development, to optimize for low memory usage was
an important concern. Scalable data structures are often composed of nodes
(heap allocated chunks of memory) containing references (pointers) to other
nodes. This representation, while able to scale well with the number of elements
in a data structure, is extremely wasteful: meta data easily account for 50% of
the space in memory if the average element size is small. However when a data
structure is used to hold a very small number of elements, it is possible
to switch to a different, more compact representation. Because the number of
elements for which the alternating representation is used is constant and small,
the time complexity of the data structure is the same. Moreover, the constant
times of working with such compact representation of a small number of elements,
even when a full scan of the elements is needed in order to access or modify the
data structure, are well compensated by the cache locality of sequentially
accessing a linear array of bytes. This allows to save memory, while
transparently switching to a linked representation once a given maximum size
is reached.

Traditionally Redis, as compact representation of hashes, lists, and sorted sets
having few elements, used a data structure called *ziplist*. A ziplist is
basically a single heap allocated chunk of memory containing a list of string
elements. It can be used to represent maps by alternating keys and values, or
ordered lists of elements. The ziplist data structure served us very well for
years, however recently an user signaled a crash in the context of accessing
ziplists. The bug happened with error corrected memory modules of the latest
generation, and RDB files are protected by a CRC64 checksum. So we started an
investigation in order to discover for bugs in the `ziplist.c` file.

After weeks of work, I (Salvatore) analytically discovered a bug that is not
related to the user crash. Oran Agra and Yuval Inbar, that are also contributors
of this specification, joined the effort of auditing the code. Salvatore also
wrote several fuzz testers modeling the layout of the user data. Even if the
fuzzing techniques used could easily find the very complex to replicate bug
that was found analytically, no crash was ever seen using the Hash data type,
the one used during the crash reported by the user.

Even if apparently `ziplist.c` does not contain bugs, or at least we cannot find
them, nor there are often reports of crashes related to a potential bug in this
part of the code, during the review all the programmers involved agreed that
the ziplist code was so complex and had such non trivial side effects that it
was a wise idea to switch to something else. The reason why it was hard to
audit was that a ziplist has the following layout:

    <header> <entry> <entry> ... <entry> <end-of-ziplist>

However it is important for Redis that a ziplist can be accessed backward, from
the latest element to the first, in order to model commands such as `LRANGE`
in a way to avoid scanning the whole ziplist to just fetch a few elements from
the tail. So each entry, was actually composed of the following two parts:

    <previous-entry-length> <entry-data>

The previous entry length could change in size from 1 to 5 bytes, in order to
use little space to encode small previous entries lengths. However inserting
or deleting elements in the middle, while using this particular encoding, may
have a cascading effect, where the previous length and even the number of bytes
the previous length is encoded, may change and may *cascade* to the next
elements. This was the main source of complexity of ziplist. However other
alternatives implementations exist that can prevent this problem and only
use local information for each entry.

Listpack takes the good ideas of ziplists and reimplement it in order to create
a more compact, faster to parse implementation, that maps directly to simple
to audit and understand code. Moreover the single entries representation in
listpack were redesigned in order to better exploit the kind of data Redis
users normally store in lists and hashes. This document describes the new format.

从 Redis 早期开发阶段开始，如何尽量降低内存占用就是一个重要议题。传统的可扩展数据结构通常由多个堆分配的节点组成，并通过指针互相引用；这种表示方式在元素数量变多时扩展性很好，但在元素平均尺寸较小时会非常浪费空间，元数据甚至可能轻易占去一半以上内存。相对地，当一个数据结构只需要承载很少量元素时，就可以切换到另一种更紧凑的表示，而且因为适用范围本身就是“小而固定”的元素数量，所以时间复杂度并不会因此改变。哪怕访问或修改时需要完整扫描元素，顺序遍历线性字节数组带来的缓存局部性，也足以抵消这种紧凑表示在常数因子上的开销。因此，Redis 很适合在元素较少时采用紧凑布局，在达到某个阈值后再透明地切换回链式表示。

在 Redis 里，这种面向少量元素的紧凑表示长期以来由 *ziplist* 承担。ziplist 本质上是一整块连续分配的堆内存，内部保存了一串字符串元素；通过 key/value 交替存放，它可以表示 map，也可以表示有序列表。ziplist 多年来工作良好，但后来有用户在访问 ziplist 时报告了 crash。由于问题发生在带纠错能力的新一代内存模块上，同时 RDB 文件也有 CRC64 校验保护，开发者开始系统排查 `ziplist.c` 的潜在 bug。Salvatore 在分析过程中发现了一个与该用户 crash 无关的问题，Oran Agra 和 Yuval Inbar 也一起加入审计，并编写 fuzz tester 模拟用户数据布局。虽然这些 fuzzing 手段本应能发现那个通过分析找出的复杂 bug，但在用户实际发生 crash 的 Hash 使用场景里，始终没能重现崩溃。

即便如此，参与 review 的开发者仍然一致认为 ziplist 过于复杂，而且带有很多不直观的副作用，因此值得用新的结构替换。ziplist 之所以难审计，核心就在于它既需要保持 `header + entry + ... + end` 这样的线性布局，又必须支持从尾到头的反向访问，以便像 `LRANGE` 这样的命令能直接从尾部读取少量元素，而不必扫描整个结构。为此，每个 entry 内部又要额外保存 `<previous-entry-length> <entry-data>`。问题在于，前一个 entry 的长度字段是 1 到 5 字节的变长编码，中间插入或删除元素时，前项长度值以及编码这个长度所需的字节数都可能变化，并进一步级联影响后续元素，这正是 ziplist 复杂度的根源。listpack 的目标，就是保留 ziplist 的优点，同时重新设计 entry 表示方式，让格式更紧凑、解析更快、实现更容易审计，也更符合 Redis 用户在 list 和 hash 中真实存储的数据形态。

General structure
===

A listpack is encoded into a single linear chunk of memory. It has a fixed
length header of six bytes (instead of ten bytes of ziplist, since we no
longer need a pointer to the start of the last element). The header is
followed by the listpack elements. In theory the data structure does not need
any terminator, however for certain concerns, a special entry marking the
end of the listpack is provided, in the form of a single byte with value
FF (255). The main advantages of the terminator are the ability to scan the
listpack without holding (and comparing at each iteration) the address of
the end of the listpack, and to recognize easily if a listpack is well
formed or truncated. These advantages are, in the idea of the writer, worth
the additional byte needed in the representation.

    <tot-bytes> <num-elements> <element-1> ... <element-N> <listpack-end-byte>

The six byte header, composed of the tot-bytes and num-elements fields is
encoded in the following way:

* `tot-bytes`: 32 bit unsigned integer holding the total amount of bytes
representing the listpack. Including the header itself and the terminator.
This basically is the total size of the allocation needed to hold the listpack
and allows to jump at the end in order to scan the listpack in reverse order,
from the last to the first element, when needed.
* `num-elements`:  16 bit unsigned integer holding the total number of elements
the listpack holds. However if this field is set to 65535, which is the greatest
unsigned integer representable in 16 bit, it means that the number of listpack
elements is not known, so a LIST-LENGTH operation will require to fully scan
the listpack. This happens when, at some point, the listpack has a number of
elements equal or greater than 65535. The num-elements field will be set again
to a lower number the first time a LIST-LENGTH operation detects the elements
count returned in the representable range.

All integers in the listpack are stored in little endian format, if not
otherwise specified (certain special encodings are in big endian because
it is more natural to represent them in this way for the way the specification
maps to C code).

listpack 会被编码为一整块线性的连续内存。它的头部固定为 6 个字节，比 ziplist 的 10 字节更短，因为这里不再需要记录最后一个元素起始位置的指针。header 后面依次是各个 element，末尾再跟一个特殊的结束标记字节 `FF`。从理论上说，这个 terminator 不是必须的，但它有两个直接好处：扫描 listpack 时不需要始终持有并比较尾地址，同时也更容易判断一个 listpack 是否完整、是否被截断。作者认为，这两个优点足以抵消额外多出的 1 个字节。

整体布局可以概括为 `<tot-bytes> <num-elements> <element-1> ... <element-N> <listpack-end-byte>`。其中 `tot-bytes` 是 32 bit 无符号整数，表示整个 listpack 占用的总字节数，包含 header 和 terminator，因此它也等价于保存该结构所需的总分配大小，并允许程序在需要反向遍历时直接跳到末尾。`num-elements` 是 16 bit 无符号整数，表示 listpack 中的元素总数；如果它被设置为 65535，就表示当前元素个数未知，此时 LIST-LENGTH 操作必须完整扫描整个 listpack。出现这种情况通常是因为某个时刻元素数曾经达到或超过 65535，之后只有在一次完整扫描确认实际元素数重新落回可表示范围时，这个字段才会被改回真实值。

除非规范另行说明，listpack 中的整数都按 little endian 存储。只有少数特殊编码会使用 big endian，因为那样更符合这份规范映射到 C 代码时的实现方式。

Elements representation
===

Each element in a listpack has the following structure:

    <encoding-type><element-data><element-tot-len>
    |                                            |
    +--------------------------------------------+
                (This is an element)

The element type and element total length are always present. The element
data itself sometimes is missing, since certain small elements are directly
represented inside the spare bits of the encoding type.

The encoding type is basically useful to understand what kind of data follows
since strings can be encoded as little endian integers, and strings can have
multiple string length fields bits in order to reduce space usage.
The element data is the data itself, like an integer or an array of bytes
representing a string. Finally the element total length, is used in order to
traverse the list backward from the end of the listpack to its head, and
is needed since otherwise there is no unique way to parse the entry from
right to left, so we need to be able to jump to the left of the specified
amount of bytes.

Each element can always be parsed left-to-right. The first two bits of the
first byte select the encoding. There are a total of 3 possibilities. The
first two encodings represents small strings. The third encoding instead is
used in order to specify other sub-encodings.

每个 listpack element 都由三部分组成：`encoding-type`、`element-data` 和 `element-tot-len`。其中 type 和 total length 总是存在，但 data 部分有时会省略，因为某些很小的元素可以直接塞进 encoding type 的空闲 bit 里。encoding type 的作用，是告诉解析器后面跟着什么类型的数据，因为字符串既可能按 little endian 整数编码，也可能采用不同长度字段的字符串编码，以便节省空间；element data 才是真正的内容本体，比如整数值或字符串字节序列；而 element total length 的存在，则是为了支持从 listpack 末尾向前遍历，如果没有它，就无法从右往左唯一地解析 entry。另一方面，element 从左向右始终是可解析的：首字节前两位先决定大类编码，总共只有三种可能，前两类对应较短的字符串，第三类则进一步派生出更多子编码。

Small numbers
---

Strings that can be represented as small numbers, such as "65" or "1" are a
very common, so they have a special encoding that allows to specify such
strings representing numbers from 0 to 127 as a single byte:

    0|xxxxxxx

Where `xxxxxxx` is a 7 bit unsigned integer. We can test for this encoding
just checking that the most significant bit of the first byte of the
entry is zero.

A few examples:

    "\x03" -- The string "3"
    "\x12" -- The string "18"

像 `"65"`、`"1"` 这样本质上只是小整数的字符串，在 Redis 中非常常见，因此 listpack 为它们提供了专门编码。格式是 `0|xxxxxxx`，其中 `xxxxxxx` 是一个 7 bit 无符号整数，所以可以直接表示 0 到 127 范围内的数值字符串，并且整个 entry 只需要 1 个字节。判断方法也很直接，只要检查首字节最高位是否为 0 即可。上面的例子里，`\x03` 表示字符串 `"3"`，`\x12` 表示字符串 `"18"`。

Tiny strings
---

Small strings are also very common elements inside objects represented inside
Redis collections, so the overhead to specify their length is just a single
byte:

    10|xxxxxx <string-data>

This encoding represents strings up to 63 characters in length, since `xxxxxx`
is a 6 bit unsigned integer. The string data is the byte by byte string itself,
and may be missing in the special case of the empty string.

A few examples:

    "\x40" -- The empty string
    "\x45hello" -- The string "hello"

短字符串同样在 Redis 集合对象中非常常见，因此它们的长度信息也被压缩到了单字节开销。这里的格式是 `10|xxxxxx <string-data>`，其中 `xxxxxx` 是 6 bit 无符号整数，因此最多可以表示长度为 63 的字符串；后面的 `string-data` 就是逐字节存储的原始字符串内容，如果字符串为空，则这一部分可以不存在。上面的例子中，`\x40` 表示空字符串，`\x45hello` 表示字符串 `"hello"`。

Multi byte encodings
---

If the most significant two bits of the first byte are both set, then the
remaining bits select one of the following sub encodings.

The first three sub encodings happen when the first two bits are both "11" but
the following bits are never "11".

    110|xxxxx yyyyyyyy -- 13 bit signed integer
    1110|xxxx yyyyyyyy -- string with length up to 4095

In this encoding, `xxxx|yyyyyyyy` represent an unsigned integer where `xxxx`
are the most significant bits and `yyyyyyyy` are the least significant bits.

Finally, when the first four bits are all set, the following sub encodings
represented by the remaining four bits are defined:

    1111|0000 <4 bytes len> <large string>
    1111|0001 <16 bits signed integer>
    1111|0010 <24 bits signed integer>
    1111|0011 <32 bits signed integer>
    1111|0100 <64 bits signed integer>
    1111|0101 to 1111|1110 are currently not used.
    1111|1111 End of listpack

如果首字节的最高两位都为 `1`，那么它就不再属于前面的小整数或短字符串编码，而是进入多字节编码分支，由剩余 bit 进一步区分具体格式。当前两位为 `11`，但后续位不是 `11` 时，会落到前面的几类子编码中，例如 `110|xxxxx yyyyyyyy` 表示 13 bit 有符号整数，`1110|xxxx yyyyyyyy` 表示长度最多为 4095 的字符串，其中 `xxxx|yyyyyyyy` 共同拼成一个无符号整数，前者是高位，后者是低位。再进一步，如果首字节前四位全为 `1`，那么剩余四位会定义更大的字符串和更宽的整数编码：`1111|0000` 后跟 4 字节长度和大字符串，`1111|0001` 到 `1111|0100` 分别对应 16/24/32/64 bit 有符号整数，`1111|0101` 到 `1111|1110` 目前保留未用，而 `1111|1111` 则作为整个 listpack 的结束标记。

Element total length field
---

As already specified, the last part of an entry is a representation of its own
size, so that the listpack can be traversed from right to left. This field
has a variable length, so that we use just a single byte for it if the length
of the field is small, and progressively use more bytes for bigger entries.
The total length field is designed to be parsed from right to left, since this
is how we use it, and cannot be parsed the other way around, from left to
right. However, when we parse the entry from left to right, we already know its
length at the time we need to parse the total length field, so we can also
compute how much bytes are needed in order to represent its total length
field using the variable encoding. This allows to just skip this amount of bytes
without attempting to parse it. We'll make it more clear with examples later in
this section.

The variable length is stored from right to left, and the most significant bit
of each byte is used in order to signal if there are more bytes. This means that
we use only 7 bits in every byte. A entry length smaller than 128 can just be
encoded as an 8-bit unsigned integer having the entry value.

    "\x20" -- 32 bytes entry length

However if I want to encode a entry length with the value of, for example, 500,
two bytes will be required. The binary representation of 500 is the following:

    111110100

We can split the representation in two 7-bit halves:

    0000011 1110100

Note that, since we parse the entry length from right to left, the entry is
stored in big endian (but it's not vanilla big endian since only 7 bits are
used and the 8th bit is used to signal the *more bytes* condition).

However we need to also add the bit to signal if there are more bytes, so
the final representation will be:

    [0]0000011          [1]1110100
     |                   |
     `- no more bytes    `- more bytes to the left!

The actual encoding will be:

    "\xf4\x03" -- 500 bytes entry length

Let's take for example a very simple entry encoding the string "hello":

    "\x45hello" -- The string "hello"

The raw entry is 6 bytes: the encoding byte followed by the raw data.
In order for the entry to be complete, we need to add the entry length field
at the end, that in this case is just the byte "06". So the final complete
entry will be:

    "\x45hello\x06" -- A complete entry representing "hello"

Note that we can easily parse the entry from right to left, by reading the
length of 6, and jumping 6 bytes on the left to reach the start of the entry,
but we can also parse the entry from left to right, since after we parsed
the entry data of six bytes, we know how much bytes are used in order to
encode its length by using the following table:

    From 0 to 127: 1 byte
    From 128 to 16383): 2 bytes
    From 16383 to 2097151: 3 bytes
    From 2097151 to 268435455: 4 bytes
    From 268435455 to 34359738367: 5 bytes

No entry can be longer than 34359738367 bytes.

每个 entry 的最后一部分都会保存它自身的总长度，这样 listpack 才能从右向左遍历。这个长度字段采用可变长度编码：长度值较小时只占 1 个字节，entry 越大则使用更多字节。它的设计目标本来就是为了从右向左解析，所以不能直接反过来按从左向右的方式解码；不过在正向扫描 entry 时，程序其实已经知道当前 entry 的总长度，因此不需要真的去“读懂”这个字段，只要根据长度值推算出它会占用几个字节，然后直接跳过去即可。

这个可变长度字段按从右到左的方向存储，并且每个字节的最高位都用来表示左边是否还有更多字节，因此真正用于承载数值的只有每字节 7 个 bit。比如长度小于 128 时，可以直接把长度值编码进单字节中，`\x20` 就表示长度为 32 的 entry。如果要编码 500，则需要先把二进制 `111110100` 拆成两个 7 bit 片段 `0000011 1110100`；又因为解析方向是从右往左，所以整体存储表现为一种带 continuation bit 的 big endian 形式。把“左边是否还有更多字节”这一位也加进去之后，最终编码就是 `\xf4\x03`。

以 `"hello"` 为例，`\x45hello` 只是原始 entry 数据，总长度一共 6 字节；要成为完整 entry，还必须在末尾追加它自己的长度字段，因此最终结果是 `\x45hello\x06`。这样从右向左解析时，只要先读出最后的长度 6，就能向左跳回 entry 起点；从左向右解析时，程序在读完前面 6 个字节后，也能根据上面的映射表推算 total length field 需要几个字节。这个字段的取值范围决定了单个 entry 最大不能超过 34359738367 字节。

Implementation requirements
===

The wish list about the implementation is, with points in decreasing order of
importance, the following:

1. Crash resistant against wrong encodings. This was not the case with ziplist
implementation.
2. Understandable and easily auditable. Well commented code.
3. Fast. Avoid unnecessary copying. For instance, when adding to head, detect if
realloc() is a non-OP (when advanced malloc functionalities are available) and
instead use malloc() and avoid a copy of the data by copying directly at the
right offset.
4. Availability of an update-element operation, so that if an element is updated
with one of the same size (very common with Hashes, think HINCRBY) there is
no memory copying involved.

Notes about understandability:

Note that understandability cannot be obtained without simplicity of the design,
however the design outlined in this document is thought to have a
straightforward translation to a simple and robust implementation.

Notes about crash resistance:

It is worth noting that crash resistance has limitations: for example a corrupted
listpack header may make the program jump to invalid addresses. In this context
for crash resistance we mean that as long as the corruption does not force the
program to jump to illegal addresses, wrong encodings are detected when possible
(that is, when the corruption does not happen to map to valid entries).
For instance a wrong string length will be detected every time the amount of
remaining bytes in the listpack is not compatible with the announced string
length. The API should always be able to report such errors instead of crashing
the program.

这份规范对实现的期待，按优先级从高到低大致有四点：第一，必须对错误编码具备 crash resistance，而这正是 ziplist 过去做得不够好的地方；第二，实现要容易理解、容易审计，并配有清晰注释；第三，要尽量快，避免不必要的数据拷贝，例如头部插入时应尽可能识别 `realloc()` 是否实际是 no-op，或者改用 `malloc()` 并直接把数据复制到正确偏移处；第四，要支持 update-element 这类原地更新操作，这样当元素被替换成一个相同大小的新值时，例如 Hash 上常见的 `HINCRBY` 场景，就可以完全避免额外内存复制。

关于“易理解”这一点，作者特别强调：没有设计上的简洁，就不可能真正做到可理解；而本文档提出的布局，就是为了能够比较自然地落地成一个简单、稳健、并且方便审计的实现。

至于 crash resistance，也要明确它不是无限制的。如果 listpack 的 header 已经损坏，程序仍然可能被迫跳转到非法地址；因此这里所谓的 crash resistance，指的是只要损坏尚未导致程序访问非法地址，就应当尽可能识别错误编码，而不是把损坏后的字节误当成合法 entry。比如，只要字符串声明的长度与 listpack 剩余字节数不匹配，就应该检测并报告错误，API 应始终优先返回错误，而不是让程序直接崩溃。

Credits
===

This specification was written by Salvatore Sanfilippo. Oran Agra and Yuval
Inbar, together with the author of this spec analyzed the ziplist implementation
in order to search for bugs and to understand how the specification could be
improved.

Yuval provided the idea of allowing backward traversal by using
only information which is local to the entry (the entry length at the end
of the entry itself) instead of global informations (such as the length
of the previous entry, as it was in ziplist).

Yuval also suggested to use a progressive length integer for the back length.

Oran provided ideas about the optimization of the implementation.

本规范由 Salvatore Sanfilippo 编写。Oran Agra 与 Yuval Inbar 一起参与了对 ziplist 实现的分析，以便查找 bug，并反向思考这份规范还可以如何改进。其中，Yuval 提出了一个关键思路：反向遍历不应该依赖像“前一个 entry 长度”这种全局信息，而应该只依赖 entry 本地就能获得的信息，也就是把 entry 自身长度放在尾部；他还建议 back length 采用渐进式长度整数编码。Oran 则提供了实现优化方面的多项思路。


APPENDIX A: potential optimizations not exploited
===

There are certain improvements that we left out of this specification in
order to enhance the simplicity of this data structure.

为了保持数据结构本身的简洁性，规范有意放弃了一些理论上可行的优化方案。

Different encodings for positive and negative integers.
---

In theory it is possible to better exploit the fact we have free additional
encoding type bits, in order to distinguish between positive and negative
integers and always represent them as unsigned. In this way we could improve the
range of the integers we can represent with a given number of bytes. A former
version of this specification used an encoding like the following:

    1111|0001 <16 bits unsigned integer>
    1111|0010 <16 bits negative integer>
    1111|0011 <24 bits unsigned integer>
    1111|0100 <24 bits negative integer>
    1111|0101 <32 bits unsigned integer>
    1111|0110 <32 bits negative integer>
    1111|0111 <64 bits unsigned integer>
    1111|1000 <64 bits negative integer>

However at a second thought this was believed to make the implementation more
complex and potentially slower, so the slightly less efficient representation of
storing signed integers was chosen instead.

理论上，我们可以更激进地利用空闲的 encoding type bit，把正整数和负整数分开编码，并始终以无符号形式存储，这样在同样字节数下就能覆盖更大的整数范围。规范的早期版本确实尝试过上面这种区分正负数的设计，但最终还是放弃了，因为它会让实现更复杂，也可能拖慢性能。相比之下，当前直接存储有符号整数的方式虽然略微低效，却更简单、更稳妥。

Packed characters
---

Many element in a listpack, notably hash field names representing objects inside
Redis, are going to use a subset of characters in the range `A-z`. Examples
are strings such as `name`, `suername` and so forth.

Using six bits per character it is possible to represent the alphabet consisting
of all the lower and upper case letters, the numbers from 0 to 9, and a few more
chars like `-`, `_`, `.`. So an additional encoding representing strings using
six bits per character could be added in order to improve the space efficiency
of strings considerably.

This was not added mainly for performance considerations, since the complexity
added is believed to be manageable and not a likely source of potential bugs.

listpack 中有很多元素，尤其是 Redis 对象里的 hash field name，实际只会使用 `A-z` 范围内的一小部分字符，例如 `name`、`suername` 这一类。理论上，只要为每个字符分配 6 个 bit，就足够表示大小写字母、数字以及 `-`、`_`、`.` 等附加字符，因此完全可以设计一种“每字符 6 bit”的字符串编码，大幅提升空间利用率。这个想法最后没有进入规范，主要不是因为它太危险，而是出于性能权衡：虽然新增复杂度本身仍然可控，也未必会成为 bug 来源，但整体收益仍不足以支撑实现成本。

Skip index
---

Accessing far elements in a long listpack is O(N), so it looks natural to add
some way in order to speedup this kind of lookups with skip tables. While this
is usually a great idea for rarely changing packed representations of data,
listpacks are going to be used in situations where data is often changed in the
middle (Redis Hash and List data types both stress this usage pattern).

Updating the skip indexes could be error prone and even costly, and with the
default settings Redis only uses relatively small listpacks where the access
locality well compensates the need for scanning.

When a memory saving representation is needed, with the ability to scale to
many elements, the author believes that a linked data structure where listpacks
are used as nodes is the preferred approach: it improves separation of concerns
between the two representations and may be simpler to manage. In this regard
listpacks are very friendly because they can be split and merged easily with
linear copies without offsets adjustments.

在很长的 listpack 中访问远处元素是 O(N) 操作，因此看上去很自然会想到引入 skip index 或 skip table 来加速查找。对于那些很少发生中间修改的紧凑型数据结构，这通常是好主意；但 listpack 的典型使用场景恰恰不是这样，Redis 的 Hash 和 List 都经常在中间位置更新元素。如果还要同步维护 skip index，不但成本高，而且容易出错。更何况在 Redis 默认配置下，listpack 通常都比较小，顺序扫描带来的代价往往已经被良好的局部性抵消了。作者因此更倾向于另一种思路：如果既想保留节省内存的表示，又要扩展到大量元素，那么不如使用链式数据结构，并把 listpack 作为节点。这样两种表示各自分工更清晰，管理也更简单；而 listpack 本身又很适合做节点，因为它们可以通过线性复制轻松拆分和合并，而不需要调整 offset。
