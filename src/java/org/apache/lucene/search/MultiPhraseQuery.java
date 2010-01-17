package org.apache.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.*;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.PositionsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.ToStringUtils;
import org.apache.lucene.util.PriorityQueue;
import org.apache.lucene.util.Bits;

/**
 * MultiPhraseQuery is a generalized version of PhraseQuery, with an added
 * method {@link #add(Term[])}.
 * To use this class, to search for the phrase "Microsoft app*" first use
 * add(Term) on the term "Microsoft", then find all terms that have "app" as
 * prefix using IndexReader.terms(Term), and use MultiPhraseQuery.add(Term[]
 * terms) to add them to the query.
 *
 * @version 1.0
 */
public class MultiPhraseQuery extends Query {
  private String field;
  private ArrayList<Term[]> termArrays = new ArrayList<Term[]>();
  private ArrayList<Integer> positions = new ArrayList<Integer>();

  private int slop = 0;

  /** Sets the phrase slop for this query.
   * @see PhraseQuery#setSlop(int)
   */
  public void setSlop(int s) { slop = s; }

  /** Sets the phrase slop for this query.
   * @see PhraseQuery#getSlop()
   */
  public int getSlop() { return slop; }

  /** Add a single term at the next position in the phrase.
   * @see PhraseQuery#add(Term)
   */
  public void add(Term term) { add(new Term[]{term}); }

  /** Add multiple terms at the next position in the phrase.  Any of the terms
   * may match.
   *
   * @see PhraseQuery#add(Term)
   */
  public void add(Term[] terms) {
    int position = 0;
    if (positions.size() > 0)
      position = positions.get(positions.size()-1).intValue() + 1;

    add(terms, position);
  }

  /**
   * Allows to specify the relative position of terms within the phrase.
   * 
   * @see PhraseQuery#add(Term, int)
   * @param terms
   * @param position
   */
  public void add(Term[] terms, int position) {
    if (termArrays.size() == 0)
      field = terms[0].field();

    for (int i = 0; i < terms.length; i++) {
      if (terms[i].field() != field) {
        throw new IllegalArgumentException(
            "All phrase terms must be in the same field (" + field + "): "
                + terms[i]);
      }
    }

    termArrays.add(terms);
    positions.add(Integer.valueOf(position));
  }

  /**
   * Returns a List of the terms in the multiphrase.
   * Do not modify the List or its contents.
   */
  public List<Term[]> getTermArrays() {
	  return Collections.unmodifiableList(termArrays);
  }

  /**
   * Returns the relative positions of terms in this phrase.
   */
  public int[] getPositions() {
    int[] result = new int[positions.size()];
    for (int i = 0; i < positions.size(); i++)
      result[i] = positions.get(i).intValue();
    return result;
  }

  // inherit javadoc
  @Override
  public void extractTerms(Set<Term> terms) {
    for (final Term[] arr : termArrays) {
      for (final Term term: arr) {
        terms.add(term);
      }
    }
  }


  private class MultiPhraseWeight extends Weight {
    private Similarity similarity;
    private float value;
    private float idf;
    private float queryNorm;
    private float queryWeight;

    public MultiPhraseWeight(Searcher searcher)
      throws IOException {
      this.similarity = getSimilarity(searcher);

      // compute idf
      final int maxDoc = searcher.maxDoc();
      for(final Term[] terms: termArrays) {
        for (Term term: terms) {
          idf += this.similarity.idf(searcher.docFreq(term), maxDoc);
        }
      }
    }

    @Override
    public Query getQuery() { return MultiPhraseQuery.this; }

    @Override
    public float getValue() { return value; }

    @Override
    public float sumOfSquaredWeights() {
      queryWeight = idf * getBoost();             // compute query weight
      return queryWeight * queryWeight;           // square it
    }

    @Override
    public void normalize(float queryNorm) {
      this.queryNorm = queryNorm;
      queryWeight *= queryNorm;                   // normalize query weight
      value = queryWeight * idf;                  // idf for document 
    }

    @Override
    public Scorer scorer(IndexReader reader, boolean scoreDocsInOrder, boolean topScorer) throws IOException {
      if (termArrays.size() == 0)                  // optimize zero-term case
        return null;

      DocsEnum[] docs = new DocsEnum[termArrays.size()];
      for (int i=0; i<docs.length; i++) {
        Term[] terms = termArrays.get(i);

        final DocsEnum docsEnum;
        if (terms.length > 1) {
          docsEnum = new UnionDocsEnum(reader, terms);
        } else {
          docsEnum = reader.termDocsEnum(reader.getDeletedDocs(),
                                         terms[0].field(),
                                         new BytesRef(terms[0].text()));
        }

        if (docsEnum == null) {
          return null;
        }

        docs[i] = docsEnum;
      }

      if (slop == 0)
        return new ExactPhraseScorer(this, docs, getPositions(), similarity,
                                     reader.norms(field));
      else
        return new SloppyPhraseScorer(this, docs, getPositions(), similarity,
                                      slop, reader.norms(field));
    }

    @Override
    public Explanation explain(IndexReader reader, int doc)
      throws IOException {
      ComplexExplanation result = new ComplexExplanation();
      result.setDescription("weight("+getQuery()+" in "+doc+"), product of:");

      Explanation idfExpl = new Explanation(idf, "idf("+getQuery()+")");

      // explain query weight
      Explanation queryExpl = new Explanation();
      queryExpl.setDescription("queryWeight(" + getQuery() + "), product of:");

      Explanation boostExpl = new Explanation(getBoost(), "boost");
      if (getBoost() != 1.0f)
        queryExpl.addDetail(boostExpl);

      queryExpl.addDetail(idfExpl);

      Explanation queryNormExpl = new Explanation(queryNorm,"queryNorm");
      queryExpl.addDetail(queryNormExpl);

      queryExpl.setValue(boostExpl.getValue() *
                         idfExpl.getValue() *
                         queryNormExpl.getValue());

      result.addDetail(queryExpl);

      // explain field weight
      ComplexExplanation fieldExpl = new ComplexExplanation();
      fieldExpl.setDescription("fieldWeight("+getQuery()+" in "+doc+
                               "), product of:");

      PhraseScorer scorer = (PhraseScorer) scorer(reader, true, false);
      if (scorer == null) {
        return new Explanation(0.0f, "no matching docs");
      }
      Explanation tfExplanation = new Explanation();
      int d = scorer.advance(doc);
      float phraseFreq = (d == doc) ? scorer.currentFreq() : 0.0f;
      tfExplanation.setValue(similarity.tf(phraseFreq));
      tfExplanation.setDescription("tf(phraseFreq=" + phraseFreq + ")");
      fieldExpl.addDetail(tfExplanation);
      fieldExpl.addDetail(idfExpl);

      Explanation fieldNormExpl = new Explanation();
      byte[] fieldNorms = reader.norms(field);
      float fieldNorm =
        fieldNorms!=null ? similarity.decodeNormValue(fieldNorms[doc]) : 1.0f;
      fieldNormExpl.setValue(fieldNorm);
      fieldNormExpl.setDescription("fieldNorm(field="+field+", doc="+doc+")");
      fieldExpl.addDetail(fieldNormExpl);

      fieldExpl.setMatch(Boolean.valueOf(tfExplanation.isMatch()));
      fieldExpl.setValue(tfExplanation.getValue() *
                         idfExpl.getValue() *
                         fieldNormExpl.getValue());

      result.addDetail(fieldExpl);
      result.setMatch(fieldExpl.getMatch());

      // combine them
      result.setValue(queryExpl.getValue() * fieldExpl.getValue());

      if (queryExpl.getValue() == 1.0f)
        return fieldExpl;

      return result;
    }
  }

  @Override
  public Query rewrite(IndexReader reader) {
    if (termArrays.size() == 1) {                 // optimize one-term case
      Term[] terms = termArrays.get(0);
      BooleanQuery boq = new BooleanQuery(true);
      for (int i=0; i<terms.length; i++) {
        boq.add(new TermQuery(terms[i]), BooleanClause.Occur.SHOULD);
      }
      boq.setBoost(getBoost());
      return boq;
    } else {
      return this;
    }
  }

  @Override
  public Weight createWeight(Searcher searcher) throws IOException {
    return new MultiPhraseWeight(searcher);
  }

  /** Prints a user-readable version of this query. */
  @Override
  public final String toString(String f) {
    StringBuilder buffer = new StringBuilder();
    if (!field.equals(f)) {
      buffer.append(field);
      buffer.append(":");
    }

    buffer.append("\"");
    Iterator<Term[]> i = termArrays.iterator();
    while (i.hasNext()) {
      Term[] terms = i.next();
      if (terms.length > 1) {
        buffer.append("(");
        for (int j = 0; j < terms.length; j++) {
          buffer.append(terms[j].text());
          if (j < terms.length-1)
            buffer.append(" ");
        }
        buffer.append(")");
      } else {
        buffer.append(terms[0].text());
      }
      if (i.hasNext())
        buffer.append(" ");
    }
    buffer.append("\"");

    if (slop != 0) {
      buffer.append("~");
      buffer.append(slop);
    }

    buffer.append(ToStringUtils.boost(getBoost()));

    return buffer.toString();
  }


  /** Returns true if <code>o</code> is equal to this. */
  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MultiPhraseQuery)) return false;
    MultiPhraseQuery other = (MultiPhraseQuery)o;
    return this.getBoost() == other.getBoost()
      && this.slop == other.slop
      && termArraysEquals(this.termArrays, other.termArrays)
      && this.positions.equals(other.positions);
  }

  /** Returns a hash code value for this object.*/
  @Override
  public int hashCode() {
    return Float.floatToIntBits(getBoost())
      ^ slop
      ^ termArraysHashCode()
      ^ positions.hashCode()
      ^ 0x4AC65113;
  }
  
  // Breakout calculation of the termArrays hashcode
  private int termArraysHashCode() {
    int hashCode = 1;
    for (final Term[] termArray: termArrays) {
      hashCode = 31 * hashCode
          + (termArray == null ? 0 : Arrays.hashCode(termArray));
    }
    return hashCode;
  }

  // Breakout calculation of the termArrays equals
  private boolean termArraysEquals(List<Term[]> termArrays1, List<Term[]> termArrays2) {
    if (termArrays1.size() != termArrays2.size()) {
      return false;
    }
    ListIterator<Term[]> iterator1 = termArrays1.listIterator();
    ListIterator<Term[]> iterator2 = termArrays2.listIterator();
    while (iterator1.hasNext()) {
      Term[] termArray1 = iterator1.next();
      Term[] termArray2 = iterator2.next();
      if (!(termArray1 == null ? termArray2 == null : Arrays.equals(termArray1,
          termArray2))) {
        return false;
      }
    }
    return true;
  }
}

/**
 * Takes the logical union of multiple DocsEnum iterators.
 */

// nocommit -- this must carefully take union of attr source
// as well -- this is tricky
class UnionDocsEnum extends DocsEnum {

  private static final class DocsQueue extends PriorityQueue<DocsEnum> {
    DocsQueue(List<DocsEnum> docsEnums) throws IOException {
      initialize(docsEnums.size());

      Iterator<DocsEnum> i = docsEnums.iterator();
      while (i.hasNext()) {
        DocsEnum docs = (DocsEnum) i.next();
        if (docs.nextDoc() != DocsEnum.NO_MORE_DOCS) {
          add(docs);
        }
      }
    }

    final public DocsEnum peek() {
      return top();
    }

    @Override
    public final boolean lessThan(DocsEnum a, DocsEnum b) {
      return a.docID() < b.docID();
    }
  }

  private static final class IntQueue {
    private int _arraySize = 16;
    private int _index = 0;
    private int _lastIndex = 0;
    private int[] _array = new int[_arraySize];
    
    final void add(int i) {
      if (_lastIndex == _arraySize)
        growArray();

      _array[_lastIndex++] = i;
    }

    final int next() {
      return _array[_index++];
    }

    final void sort() {
      Arrays.sort(_array, _index, _lastIndex);
    }

    final void clear() {
      _index = 0;
      _lastIndex = 0;
    }

    final int size() {
      return (_lastIndex - _index);
    }

    private void growArray() {
      int[] newArray = new int[_arraySize * 2];
      System.arraycopy(_array, 0, newArray, 0, _arraySize);
      _array = newArray;
      _arraySize *= 2;
    }
  }

  private int _doc;
  private int _freq;
  private DocsQueue _queue;
  private IntQueue _posList;

  private final UnionPositionsEnum unionPositionsEnum;

  public UnionDocsEnum(IndexReader indexReader, Term[] terms) throws IOException {
    List<DocsEnum> docsEnums = new LinkedList<DocsEnum>();
    final Bits delDocs = indexReader.getDeletedDocs();

    for (int i = 0; i < terms.length; i++) {
      DocsEnum docs = indexReader.termDocsEnum(delDocs,
                                               terms[i].field(),
                                               new BytesRef(terms[i].text()));
      if (docs != null) {
        docsEnums.add(docs);
      }
    }

    _queue = new DocsQueue(docsEnums);
    _posList = new IntQueue();
    unionPositionsEnum = new UnionPositionsEnum();
  }

  @Override
  public PositionsEnum positions() {
    return unionPositionsEnum;
  }

  @Override
  public final int nextDoc() throws IOException {
    if (_queue.size() == 0) {
      return NO_MORE_DOCS;
    }

    // TODO: move this init into positions(): if the search
    // doesn't need the positions for this doc then don't
    // waste CPU merging them:
    _posList.clear();
    _doc = _queue.top().docID();

    // merge sort all positions together
    DocsEnum docs;
    do {
      docs = _queue.top();
      final PositionsEnum positions = docs.positions();

      final int freq = docs.freq();
      for (int i = 0; i < freq; i++) {
        _posList.add(positions.next());
      }

      if (docs.nextDoc() != NO_MORE_DOCS) {
        _queue.updateTop();
      } else {
        _queue.pop();
      }
    } while (_queue.size() > 0 && _queue.top().docID() == _doc);

    _posList.sort();
    _freq = _posList.size();

    return _doc;
  }

  private class UnionPositionsEnum extends PositionsEnum {

    @Override
    public int next() {
      return _posList.next();
    }

    @Override
    public int getPayloadLength() {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getPayload(byte[] data, int offset) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasPayload() {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public final int advance(int target) throws IOException {
    while (_queue.top() != null && target > _queue.top().docID()) {
      DocsEnum docs = _queue.pop();
      if (docs.advance(target) != NO_MORE_DOCS) {
        _queue.add(docs);
      }
    }
    return nextDoc();
  }

  @Override
  public final int freq() {
    return _freq;
  }

  @Override
  public final int docID() {
    return _doc;
  }

  /**
   * Not implemented.
   * @throws UnsupportedOperationException
   */
  @Override
  public int read(int[] arg0, int[] arg1) throws IOException {
    throw new UnsupportedOperationException();
  }
}
