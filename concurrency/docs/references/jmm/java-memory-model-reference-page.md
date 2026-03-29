# The Java Memory Model

> Cleaned local Markdown mirror of the upstream HTML page. Content is kept close to the original; only formatting and link normalization were applied.
>
> Upstream URL: [https://www.cs.umd.edu/~pugh/java/memoryModel/](https://www.cs.umd.edu/~pugh/java/memoryModel/)  
> Original local mirror: [java-memory-model-reference-page.html](./java-memory-model-reference-page.html)

This web page is a starting point for discussions of and information concerning the Java Memory Model ([Chapter 17](http://java.sun.com/docs/books/jls/third_edition/html/memory.html) of the [Java Language Specification](http://java.sun.com/docs/books/jls/)). The Java Memory Model defines how threads interact through memory. It used to be somewhat unclear and unnecessarily limiting, and so was revised. This is a reference page for that revision. The official site for JSR 133 - The Java(tm) Memory Model and Thread Specification Revision - [is here](https://www.jcp.org/en/jsr/detail?id=133).

This page is divded up into several sections:

  - Primary reference material on the memory model.
  - Pointers to the mailing list and archives.
  - Additional material on the memory model, including information on double-checked locking.
  - Older material on the memory model, now obsolete.
  - Pointers to further reading material from other sources.

---

## Reference Material
These reference materials are a good starting point for anyone trying to understand the memory model. Between them, they cover most of the major issues involved.

  - **For First-Time Visitors**
    - A [JSR-133 FAQ resource for programmers](https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html) is available. This is a good place to start for those just becoming aware of the issues. (February 11, 2004)

  - **For JVM and compiler implementors**
    - [Doug Lea's JSR-133 cookbook](https://gee.cs.oswego.edu/dl/jmm/cookbook.html), which is a guide for compiler writers who wish to implement the Java memory model.
    - Sarita Adve and Kourosh Gharachorloo wrote a tutorial on memory models in 1995 that remains an excellent reference and primer. Compaq Research Report 95/7, September 1995, [95.7 \-- Shared Memory Consistency Models: A Tutorial](http://research.compaq.com/wrl/techreports/abstracts/95.7.html).

  - **For those wishing to understand the memory model in full**
    - [A journal submission](http://dl.dropbox.com/u/1011627/journal.pdf) about the memory model that combines Jeremy Manson's dissertation, the POPL paper and the CSJP paper. For those interested in a thorough discussion of the memory model issues, this is the best bet. (October 7, 2005).
    - [The JSR-133 specification, as sent to Final Approval Ballot](https://www.cs.umd.edu/~pugh/java/memoryModel/jsr133.pdf). This is the "official specification" (August 9, 2004). It doesn't contain much in the way of explanation.
    - [New presentation/description of the semantics of final fields](https://www.cs.umd.edu/~pugh/java/memoryModel/may-12.pdf). This is a brief description of the semantics of final fields. (May 12, 2004)

---

## Mailing list
  - To join the Java memory model mailing list, visit [this page](https://mailman.cs.umd.edu/mailman/listinfo/javamemorymodel-discussion).
  - To post to the list, email [javamemorymodel-discussion@mimsy.cs.umd.edu](mailto:javamemorymodel-discussion@mimsy.cs.umd.edu) (only subscribers may post to the list).
  - The list has migrated. To access the archive of the old list, visit [this page](https://www.cs.umd.edu/~pugh/java/memoryModel/archive/). To access the archive of the new list, visit [this page](https://mailman.cs.umd.edu/mailman/private/javamemorymodel-discussion/).

---

## Additional Information
### Double-Checked Locking is Broken
Double-checked locking (also known as the multithreaded singleton pattern) is a widely employed idiom for publishing a singleton object to multiple threads.

  - **The "Double-Checked Locking is Broken" Declaration**

[This document](https://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html) describes why the double-checked locking pattern is broken unless you use explicit memory barriers (or make assumptions about your processor and compiler).
  - Descriptions of double-check idiom
    - [Reality Check](http://www.cs.wustl.edu/~schmidt/editorial-3.html), Douglas C. Schmidt, C++ Report, SIGS, Vol. 8, No. 3, March 1996.
    - [Double-Checked Locking: An Optimization Pattern for Efficiently Initializing and Accessing Thread-safe Objects](http://www.cs.wustl.edu/~schmidt/DC-Locking.ps.gz), Douglas Schmidt and Tim Harrison. _3rd annual Pattern Languages of Program Design conference_ , 1996
    - [Lazy instantiation](http://www.javaworld.com/javaworld/javatips/jw-javatip67.html), Philip Bishop and Nigel Warren, JavaWorld Magazine
    - [Programming Java threads in the real world, Part 7](http://www.javaworld.com/javaworld/jw-04-1999/jw-04-toolbox-3.html), Allen Holub, Javaworld Magazine, April 1999.

### Other information
  - [Causality test cases 1-20](https://www.cs.umd.edu/~pugh/java/memoryModel/CausalityTestCases.html)
  - Informal notes we made about legal and illegal multithreaded patterns are available in [this document](https://www.cs.umd.edu/~pugh/java/memoryModel/issues.pdf).
  - Volatile spec compliance tests
    - [AtomicLong.java](https://www.cs.umd.edu/~pugh/java/memoryModel/AtomicLong.java) - Tests for atomic read/writes of volatile Longs
    - [CoherenceVolatile.java](https://www.cs.umd.edu/~pugh/java/memoryModel/CoherenceVolatile.java) - Tests for illegal compiler optimizations involving volatiles
    - [ReadAfterWrite.java](https://www.cs.umd.edu/~pugh/java/memoryModel/ReadAfterWrite.java) - Tests for sequential consistency of volatiles
    - [Here](https://www.cs.umd.edu/~pugh/java/memoryModel/Volatiles.tar) is a tar file containing the tests and more detail about their output.
  - [Table of number of synchronization operations in Spec benchmarks](https://www.cs.umd.edu/~pugh/java/memoryModel/syncCost.html)

---

## Older Material (for the memory model historians among you)
This is a list of many of the revisions that the memory model underwent over the course of its three years in flight. They are mostly out of date. If you are interested in the model as it stands, your best bet is the reference material above.

### Draft Proposals for the Memory Model
  - [Earlier draft spec, incorporates some minor fixes from Draft Final Spec.](https://www.cs.umd.edu/~pugh/java/memoryModel/CurrentDraftSpec.pdf) (April 23, 2004)
  - [Proposed Final Draft for JSR-133](https://www.cs.umd.edu/~pugh/java/memoryModel/ProposedFinalDraft.pdf) (April 12, 2004)
  - [Documents about the Unified Memory Model Proposal for Java, including additional test cases](https://www.cs.umd.edu/~pugh/java/memoryModel/unifiedProposal) (March 16, 2004)
  - [Update of JSR-133 Public Review Document](https://www.cs.umd.edu/~pugh/java/memoryModel/PostPublicReview.pdf), includes clarifications and minor fixes, does not incorporate a new formalism. (March 16, 2004)
  - [Writeup of alternative formalism for the JSR-133 memory model](http://www.saraswat.org) (March 16, 2004)
  - [JSR-133 is in public review](https://jcp.org/aboutJava/communityprocess/review/jsr133/index.html) (March 16, 2004)
  - [Experimental version of MP without forbidden executions](https://www.cs.umd.edu/users/pugh/java/memoryModel/M-P/Feb-24-Experimental.pdf) (February 24, 2004).
  - [JSR-133 Public Review Document](https://www.cs.umd.edu/~pugh/java/memoryModel/PublicReview.pdf) (February 2, 2004).
  - The competing models of February 6, 2004.
    - [SC-](http://www.cs.uiuc.edu/~sadve/jmm)
    - Manson/Pugh model:
      - [Short Informal Description of the M/P model](https://www.cs.umd.edu/~pugh/java/memoryModel/M-P/Feb-6.pdf)
      - [Proof that M/P model has certain desirable properties](https://www.cs.umd.edu/~pugh/java/memoryModel/M-P/Feb-6-Proof.pdf)
      - [Long, formal description of the M/P model](https://www.cs.umd.edu/~pugh/java/memoryModel/M-P/Feb-6-Formalism.pdf)
  - October 17, 2003
    - [October 23th description of the Manson/Pugh core memory model (minor tweaks from previous version)](https://www.cs.umd.edu/~pugh/java/memoryModel/October23.pdf)
    - [Proof that correctly synchronized programs have sequentially consistent semantics, and that standard reordering transformations are valid](https://www.cs.umd.edu/~pugh/java/memoryModel/Proof-3.pdf)
    - [JSR-133 Community Review Document](https://www.cs.umd.edu/~pugh/java/memoryModel/CommunityReview-2.pdf)
  - August 29, 2003: [NEW one page description of the Manson/Pugh core memory model (with a one and a half page appendix)](https://www.cs.umd.edu/~pugh/java/memoryModel/August29.pdf)
  - August 8, 2003: [JSR-133 Community Review Document](https://www.cs.umd.edu/~pugh/java/memoryModel/CommunityReview.pdf)
  - August 4, 2003: [Proof that reordering is legal under Manson/Pugh](https://www.cs.umd.edu/~pugh/java/memoryModel/ReorderingIsLegal.pdf)
  - July 31, 2003: [One page description of the Manson/Pugh core memory model (with a one page appendix)](https://www.cs.umd.edu/~pugh/java/memoryModel/MansonPugh.pdf)
  - The full semantics of normal fields are in [A New Approach to the Semantics of Multithreaded Java](https://www.cs.umd.edu/~pugh/java/memoryModel/newest.pdf), by Jeremy Manson and William Pugh, Revised Jan 13, 2003.
  - The full semantics of final fields are in [Final Field Semantics](https://www.cs.umd.edu/~pugh/java/memoryModel/newFinal.pdf), by Jeremy Manson and William Pugh, Revised April 7, 2003.
  - [Multithreaded semantics for Java](https://www.cs.umd.edu/~pugh/java/memoryModel/semantics.pdf), a previous version of the semantics. (2001)
  - Weak Memory Orders and Object Oriented Programming, draft of the abstract for an OOPSLA poster session submission ([PDF](https://www.cs.umd.edu/~pugh/java/memoryModel/weak.pdf)) ([PS](https://www.cs.umd.edu/~pugh/java/memoryModel/weak.ps))
  - [The Java Memory Model is Broken](https://www.cs.umd.edu/~pugh/java/broken.pdf) by [William Pugh](https://www.cs.umd.edu/~pugh), Journal version of the following paper; cleans up the paper somewhat and removes the naive fixes suggested in that paper.
  - [Fixing the Java Memory Model](https://www.cs.umd.edu/~pugh/java/#jmm) by [William Pugh](https://www.cs.umd.edu/~pugh), [1999 ACM Java Grande](http://www.cs.ucsb.edu/conferences/java99)

### Talks
  - [Presentation given at Dagstuhl](https://www.cs.umd.edu/~pugh/java/memoryModel/Dagstuhl.pdf) (October 24th, 2003)
  - Multithreaded semantics for Java, (edited version of presentation given at MIT Sept 10th, 2000)
Slides ([PDF](https://www.cs.umd.edu/~pugh/java/memoryModel/multithreaded.pdf) or [PS](https://www.cs.umd.edu/~pugh/java/memoryModel/multithreaded.ps)) and handout ([PDF](https://www.cs.umd.edu/~pugh/java/memoryModel/multithreadedHandout.pdf) or [PS](https://www.cs.umd.edu/~pugh/java/memoryModel/multithreadedHandout.ps))
  - [JavaOne BOF on revising the Java Thread Spec, with Doug Lea (2000)](https://www.cs.umd.edu/~pugh/java/memoryModel/JavaOneBOF)
  - [JavaOne Talk, with Doug Lea (2000)](https://www.cs.umd.edu/~pugh/java/memoryModel/TS-754.pdf)
  - [OOPSLA 2000 workshop page](https://www.cs.umd.edu/~pugh/java/memoryModel/workshop/)
  - [Page for JavaOne 2000 BOF on revising the Java Thread Spec](https://www.cs.umd.edu/~pugh/java/memoryModel/JavaOneBOF)
  - [Slides from 1999 Java Grande Talk](https://www.cs.umd.edu/~pugh/java/jmmSlides.pdf)

---

## Additional Background Reading
### By Doug Lea
  - [The Java Memory Model](https://gee.cs.oswego.edu/dl/cpj/jmm.html), Section 2.2.7 of [_Concurrent Programming in Java, 2 nd edition_](https://gee.cs.oswego.edu/dl/cpj/index.html), Doug Lea, Addison Wesley, 1999
  - [Proposed revision to Section 17.4, Wait Sets and notification](https://gee.cs.oswego.edu/dl/html/jvms.html), Doug Lea

### By Cenciarelli et al
  - [An Event-Based Structual Operational Semantics of Multi-Threaded Java](http://www.pst.informatik.uni-muenchen.de/~reus/fssj.ps.gz), P. Cenciarelli, A. Knapp, B. Reus, M. Wirsing, In Jim Alves-Foss (Ed.) Formal Syntax and Semantics Of Java, LNCS 1523, pp. 157--200, Springer, Berlin, 1999\.
  - [From Sequential To Multi-Threaded Java: An Event-Based Operational Semantics](ftp://ftp.informatik.uni-muenchen.de/pub/local/pst/papers/reus/java/java_semantics.ps.gz), P. Cenciarelli, A. Knapp, B. Reus, M. Wirsing, In Proc. 6^th Int. Conf. Algebraic Methodology and Software Technology, LNCS 1376, pp. 402--417. Springer Verlag. Berlin 1998.
  - [Verifying a Compiler Optimization for Multi-Threaded Java](ftp://ftp.informatik.uni-muenchen.de/pub/local/pst/papers/reus/java/prescient.ps.gz), B. Reus, A. Knapp, P. Cenciarelli, M. Wirsing, WADT Workshop 97, LNCS.

### By Schuster et al.
  - [Java Memory Model: Precise Characterizations](http://www.cs.technion.ac.il/~assaf/publications/java.ps) by [Assaf Schuster](http://www.cs.technion.ac.il/~assaf/), Workshop on Java for High-Performance Computing, June 1999, Rhodes.
  - [Java consistency: nonoperational characterizations for Java memory behavior](http://www.cs.technion.ac.il/~assaf/publications/java.ps) by Alex Gontmakher and Assaf Schuster, ACM Transactions on Computer Systems Volume 18 , No. 4 (Nov. 2000) Pages 333 - 386.

### On other memory models
  - [CAPSL Technical Memo 16:](ftp://ftp.capsl.udel.edu/pub/doc/memos/memo016.ps.gz) (148K gzipped Postscript), **"Location Consistency -- a new Memory Model and Cache Consistency Protocol,"** _Guang R. Gao, Vivek Sarkar,_ February 16, 1998.
  - [TLA and TLA+](http://www.research.digital.com/SRC/tla/), Lamport et al.

### By Arvind et al.
  - [Improving the Java Memory Model Using CRF](ftp://csg-ftp.lcs.mit.edu/pub/papers/csgmemo/memo-428.ps), Jan-Willem Maessen, Arvind, and Xiaowei Shen, OOPSLA 2000
  - [Commit-Reconcile and Fences (CRF): A New Memory Model for Architects and Compiler Writers](http://citeseer.nj.nec.com/356398.html), Xiaowei Shen, Arvind and Larry Rudolph, December 1998, To appear in proceedings of the 26th International Symposium on Computer Architecture, May 1999, Atlanta, Georgia., (14 pages).
  - [Improving the Java Memory Model Using CRF](http://citeseer.nj.nec.com/364438.html), Jan-Willem Maessen, Arvind, and Xiaowei Shen, OOPSLA 2000

### By others
  - [Preliminary examination of the impact of "reads kill"](https://www.cs.umd.edu/~pugh/java/readsKillImpact.html) by [Dan Scales](http://www.research.digital.com/wrl/people/scales/bio.html), Digital Western Research Laboratories
  - [Javasoft Bug # 4242244](http://developer.java.sun.com/developer/bugParade/bugs/4242244.html): JLS requires Coherence, Sun's JIT doesn't provide it.
  - [Investigating Java concurrency using Abstract State Machines](http://www.eecis.udel.edu/~wallace/javaconc.ps), Yuri Gurevich, Wolfram Schulte and [Charles Wallace](http://www.eecis.udel.edu/~wallace)

---

This page maintained by [William Pugh](https://www.cs.umd.edu/~pugh). This material is based upon work supported by the National Science Foundation under Grant No. 0098162. Any opinions, findings, and conclusions or recommendations expressed in this material are those of the author(s) and do not necessarily reflect the views of the National Science Foundation.
