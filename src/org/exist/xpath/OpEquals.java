/*
 *  eXist Open Source Native XML Database
 * 
 *  Copyright (C) 2000-03, Wolfgang M. Meier (meier@ifs. tu- darmstadt. de)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.exist.xpath;

import java.util.Iterator;
import java.util.Map;

import org.apache.log4j.Category;
import org.exist.EXistException;
import org.exist.dom.ArraySet;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.SingleNodeSet;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.IndexPaths;
import org.exist.storage.analysis.SimpleTokenizer;
import org.exist.storage.analysis.TextToken;
import org.exist.util.Configuration;

/**
 *  compare two operands by =, <, > etc..
 *
 *@author     Wolfgang Meier <meier@ifs.tu-darmstadt.de>
 *@created    31. August 2002
 */
public class OpEquals extends BinaryOp {

	private static Category LOG = Category.getInstance(OpEquals.class.getName());

	protected int relation = Constants.EQ;
	protected NodeSet temp = null;

	// in some cases, we use a fulltext expression to preselect nodes
	protected ExtFulltext containsExpr = null;

	/**
	 *  Constructor for the OpEquals object
	 *
	 *@param  relation  Description of the Parameter
	 */
	public OpEquals(BrokerPool pool, int relation) {
		super(pool);
		this.relation = relation;
	}

	/**
	 *  Constructor for the OpEquals object
	 *
	 *@param  left      Description of the Parameter
	 *@param  right     Description of the Parameter
	 *@param  relation  Description of the Parameter
	 */
	public OpEquals(BrokerPool pool, Expression left, Expression right, int relation) {
		super(pool);
		this.relation = relation;
		if (left instanceof PathExpr && ((PathExpr) left).getLength() == 1)
			add(((PathExpr) left).getExpression(0));
		else
			add(left);
		if (right instanceof PathExpr && ((PathExpr) right).getLength() == 1)
			add(((PathExpr) right).getExpression(0));
		else
			add(right);
	}

	/**
	 *  Left argument is boolean: Convert right argument to a bool.
	 *
	 *@param  left     Description of the Parameter
	 *@param  right    Description of the Parameter
	 *@param  docs     Description of the Parameter
	 *@param  context  Description of the Parameter
	 *@param  node     Description of the Parameter
	 *@return          Description of the Return Value
	 */
	protected Value booleanCompare(
		Expression left,
		Expression right,
		StaticContext context,
		DocumentSet docs,
		NodeSet contextSet) throws XPathException {
		ArraySet result = new ArraySet(100);
		NodeProxy n;
		boolean lvalue;
		boolean rvalue;
		DocumentSet dset;
		SingleNodeSet set = new SingleNodeSet();
		for (Iterator i = contextSet.iterator(); i.hasNext();) {
			n = (NodeProxy) i.next();
			set.add(n);
			rvalue = left.eval(context, docs, set).getBooleanValue();
			lvalue = right.eval(context, docs, set).getBooleanValue();
			if (lvalue == rvalue) {
				result.add(n);
				n.addContextNode(n);
			}
		}
		return new ValueNodeSet(result);
	}

	/**
	 *  Description of the Method
	 *
	 *@param  left   Description of the Parameter
	 *@param  right  Description of the Parameter
	 *@return        Description of the Return Value
	 */
	protected boolean cmpBooleans(boolean left, boolean right) {
		switch (relation) {
			case Constants.EQ :
				return (left == right);
			case Constants.NEQ :
				return (left != right);
		}
		return false;
	}

	/**
	 *  Description of the Method
	 *
	 *@param  left   Description of the Parameter
	 *@param  right  Description of the Parameter
	 *@return        Description of the Return Value
	 */
	protected boolean cmpNumbers(double left, double right) {
		switch (relation) {
			case Constants.EQ :
				return (left == right);
			case Constants.NEQ :
				return (left != right);
			case Constants.GT :
				return (left > right);
			case Constants.LT :
				return (left < right);
			case Constants.GTEQ :
				return (left >= right);
			case Constants.LTEQ :
				return (left <= right);
		}
		return false;
	}

	/**
	 *  Description of the Method
	 *
	 *@param  left   Description of the Parameter
	 *@param  right  Description of the Parameter
	 *@return        Description of the Return Value
	 */
	protected boolean compareStrings(String left, String right) {
		int cmp = left.compareTo(right);
		switch (relation) {
			case Constants.EQ :
				return (cmp == 0);
			case Constants.NEQ :
				return (cmp != 0);
			case Constants.GT :
				return (cmp > 0);
			case Constants.LT :
				return (cmp < 0);
			case Constants.GTEQ :
				return (cmp >= 0);
			case Constants.LTEQ :
				return (cmp <= 0);
		}
		return false;
	}

	/**
	 *  Compare left and right statement. Comparison is done like described in
	 *  the spec. If one argument returns a node set, we handle that first.
	 *  Otherwise if one argument is a number, process that. Third follows
	 *  string, boolean is last. If necessary move right to left and left to
	 *  right.
	 *
	 *@param  docs     Description of the Parameter
	 *@param  context  Description of the Parameter
	 *@param  node     Description of the Parameter
	 *@return          Description of the Return Value
	 */
	public Value eval(StaticContext context, DocumentSet docs, NodeSet contextSet,
		NodeProxy contextNode) throws XPathException {
		if (getLeft().returnsType() == Constants.TYPE_NODELIST)
			return nodeSetCompare(getLeft(), getRight(), context, docs, contextSet);
		else if (getRight().returnsType() == Constants.TYPE_NODELIST) {
			switchOperands();
			return nodeSetCompare(getRight(), getLeft(), context, docs, contextSet);
		} else if (getLeft().returnsType() == Constants.TYPE_NUM)
			return numberCompare(getLeft(), getRight(), context, docs, contextSet);
		else if (getRight().returnsType() == Constants.TYPE_NUM)
			return numberCompare(getRight(), getLeft(), context, docs, contextSet);
		else if (getLeft().returnsType() == Constants.TYPE_STRING)
			return stringCompare(getLeft(), getRight(), context, docs, contextSet);
		else if (getLeft().returnsType() == Constants.TYPE_BOOL)
			return booleanCompare(getLeft(), getRight(), context, docs, contextSet);
		else if (getRight().returnsType() == Constants.TYPE_BOOL)
			return booleanCompare(getRight(), getLeft(), context, docs, contextSet);
		throw new RuntimeException("syntax error");
	}

	/**
	 *  Left argument is a node set. If right arg is a string-literal, call
	 *  broker.getNodesEqualTo - which is fast. If it is a number, convert it.
	 *  If it is a boolean, get the part of context which matches the left
	 *  expression, get the right value for every node of context and compare it
	 *  with the left-part.
	 *
	 *@param  left     Description of the Parameter
	 *@param  right    Description of the Parameter
	 *@param  docs     Description of the Parameter
	 *@param  context  Description of the Parameter
	 *@param  node     Description of the Parameter
	 *@return          Description of the Return Value
	 */
	protected Value nodeSetCompare(
		Expression left,
		Expression right,
		StaticContext context,
		DocumentSet docs,
		NodeSet contextSet) throws XPathException {
		NodeSet result = new ArraySet(100);
		// TODO: not correct: should test if right is a string literal
		if (right.returnsType() == Constants.TYPE_STRING ||
			right.returnsType() == Constants.TYPE_NODELIST) {
			// evaluate left expression
			NodeSet nodes = (NodeSet) left.eval(context, docs, contextSet).getNodeList();
			String cmp = right.eval(context, docs, contextSet).getStringValue();
			if (getLeft().returnsType() == Constants.TYPE_NODELIST && relation == Constants.EQ &&
				nodes.hasIndex() && cmp.length() > 0) {
				String cmpCopy = cmp;
				cmp = maskWildcards(cmp);
				// try to use a fulltext search expression to reduce the number
				// of potential nodes to scan through
				SimpleTokenizer tokenizer = new SimpleTokenizer();
				tokenizer.setText(cmp);
				TextToken token;
				String term;
				boolean foundNumeric = false;
				// setup up an &= expression using the fulltext index
				containsExpr = new ExtFulltext(pool, Constants.FULLTEXT_AND);
				for (int i = 0; i < 5 && (token = tokenizer.nextToken(true)) != null; i++) {
					// remember if we find an alphanumeric token
					if(token.getType() == TextToken.ALPHANUM)
						foundNumeric = true;
					containsExpr.addTerm(token.getText());
				} 
				// check if all elements are indexed. If not, we can't use the
				// fulltext index.
				if(foundNumeric)
					foundNumeric = checkArgumentTypes(docs);
				if((!foundNumeric) && containsExpr.countTerms() > 0) {
					// all elements are indexed: use the fulltext index
					Value temp = containsExpr.eval(context, docs, nodes, null);
					nodes = (NodeSet) temp.getNodeList();
				}
				cmp = cmpCopy;
			}
			// now compare the input node set to the search expression
			DBBroker broker = null;
			try {
				broker = pool.get();
				result = broker.getNodesEqualTo(nodes, docs, relation, cmp);
			} catch (EXistException e) {
				throw new XPathException("An error occurred while processing expression", e);
			} finally {
				pool.release(broker);
			}
		} else if (right.returnsType() == Constants.TYPE_NUM) {
			double rvalue;
			double lvalue;
			NodeProxy ln;
			NodeSet temp = new SingleNodeSet();
			NodeSet lset = (NodeSet) left.eval(context, docs, contextSet).getNodeList();
			for (Iterator i = lset.iterator(); i.hasNext();) {
				ln = (NodeProxy) i.next();
				try {
					lvalue = Double.parseDouble(ln.getNodeValue());
				} catch (NumberFormatException nfe) {
					continue;
				}
				temp.add(ln);
				rvalue = right.eval(context, docs, temp).getNumericValue();
				if (cmpNumbers(lvalue, rvalue))
					result.add(ln);
			}
		} else if (right.returnsType() == Constants.TYPE_BOOL) {
			NodeProxy n;
			NodeProxy parent;
			boolean rvalue;
			boolean lvalue;
			long pid;
			ArraySet leftNodeSet;
			ArraySet temp;
			// get left arguments node set
			leftNodeSet = (ArraySet) left.eval(context, docs, contextSet).getNodeList();
			temp = new ArraySet(10);
			// get that part of context for which left argument's node set would
			// be > 0
			for (Iterator i = leftNodeSet.iterator(); i.hasNext();) {
				n = (NodeProxy) i.next();
				parent = contextSet.parentWithChild(n, false, true);
				if (parent != null)
					temp.add(parent);
			}
			SingleNodeSet ltemp = new SingleNodeSet();
			// now compare every node of context with the temporary set
			for (Iterator i = contextSet.iterator(); i.hasNext();) {
				n = (NodeProxy) i.next();
				ltemp.add(n);
				lvalue = temp.contains(n);
				rvalue = right.eval(context, docs, ltemp).getBooleanValue();
				if (cmpBooleans(lvalue, rvalue))
					result.add(n);
			}
		}
		return new ValueNodeSet(result);
	}

	private String maskWildcards(String expr) {
		StringBuffer buf = new StringBuffer();
		char ch;
		for(int i = 0; i < expr.length(); i++) {
			ch = expr.charAt(i);
			switch(ch) {
				case '*' :
					buf.append("\\*");
					break;
				case '%' :
					buf.append('*');
					break;
				default :
					buf.append(ch);
			}
		}
		return buf.toString();
	}
	private boolean checkArgumentTypes(DocumentSet docs) throws XPathException {
		DBBroker broker = null;
		try {
			broker = pool.get();
			Configuration config = broker.getConfiguration();
			Map idxPathMap = (Map) config.getProperty("indexer.map");
			DocumentImpl doc;
			IndexPaths idx;
			for(Iterator i = docs.iterator(); i.hasNext(); ) {
				doc = (DocumentImpl)i.next();
				idx = (IndexPaths) idxPathMap.get(doc.getDoctype().getName());
				if(idx != null && idx.isSelective())
					return true;
				if(idx != null && (!idx.getIncludeAlphaNum()))
					return true;
			}
		} catch(EXistException e) {
			LOG.warn("Exception while processing expression", e);
			throw new XPathException("An error occurred while processing expression", e);
		} finally {
			pool.release(broker);
		}
		return false;
	}
	
	/**
	 *  Left argument is a number: Convert right argument to a number for every
	 *  node in context.
	 *
	 *@param  left     Description of the Parameter
	 *@param  right    Description of the Parameter
	 *@param  docs     Description of the Parameter
	 *@param  context  Description of the Parameter
	 *@param  node     Description of the Parameter
	 *@return          Description of the Return Value
	 */
	protected Value numberCompare(
		Expression left,
		Expression right,
		StaticContext context,
		DocumentSet docs,
		NodeSet contextSet) throws XPathException {
		ArraySet result = new ArraySet(100);
		NodeProxy current;
		double rvalue;
		double lvalue;
		for (Iterator i = contextSet.iterator(); i.hasNext();) {
			current = (NodeProxy) i.next();
			rvalue = right.eval(context, docs, contextSet, current).getNumericValue();
			lvalue = left.eval(context, docs, contextSet, current).getNumericValue();
			if (cmpNumbers(lvalue, rvalue)) {
				result.add(current);
				current.addContextNode(current);
			}
			
		}
		return new ValueNodeSet(result);
	}

	/**
	 *  Description of the Method
	 *
	 *@return    Description of the Return Value
	 */
	public String pprint() {
		StringBuffer buf = new StringBuffer();
		buf.append(getLeft().pprint());
		buf.append(Constants.OPS[relation]);
		buf.append(getRight().pprint());
		return buf.toString();
	}

	/**
	 *  check relevant documents. Does nothing here.
	 *
	 *@param  in_docs  Description of the Parameter
	 *@return          Description of the Return Value
	 */
	public DocumentSet preselect(DocumentSet in_docs) {
		return in_docs;
	}

	/**
	 *  Description of the Method
	 *
	 *@return    Description of the Return Value
	 */
	public int returnsType() {
		return Constants.TYPE_NODELIST;
	}

	protected Value stringCompare(
		Expression left,
		Expression right,
		StaticContext context,
		DocumentSet docs,
		NodeSet contextSet) throws XPathException {
		LOG.debug("comparing " + docs.getLength());
		ArraySet result = new ArraySet(100);
		NodeProxy n;
		String lvalue;
		String rvalue;
		int cmp;
		SingleNodeSet temp = new SingleNodeSet();
		for (Iterator i = contextSet.iterator(); i.hasNext();) {
			n = (NodeProxy) i.next();
			temp.add(n);
			rvalue = left.eval(context, docs, temp).getStringValue();
			lvalue = right.eval(context, docs, temp).getStringValue();
			if (compareStrings(rvalue, lvalue)) {
				result.add(n);
				n.addContextNode(n);
			}
		}
		return new ValueNodeSet(result);
	}

	protected void switchOperands() {
		switch (relation) {
			case Constants.GT :
				relation = Constants.LT;
				break;
			case Constants.LT :
				relation = Constants.GT;
				break;
			case Constants.LTEQ :
				relation = Constants.GTEQ;
				break;
			case Constants.GTEQ :
				relation = Constants.LTEQ;
				break;
		}
	}
}
