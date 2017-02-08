package com.sun.tools.javac.parser;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Name;

import com.sun.source.tree.GotoTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.StatementTree;
import com.sun.tools.javac.jvm.Code;
import com.sun.tools.javac.jvm.Code.Chain;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;

/**
 * I am a convenience analyzer doing most of the heavy lifting when it comes to
 * keeping track of references between gotos and labels, anaylisis and byte code generation
 * 
 * @author marska
 *
 */
public class GotoResolver {

	private Map<GotoTree, Name> gotos = new HashMap<GotoTree, Name>();
	private Map<Name, LabeledStatementTree> targets = new HashMap<Name, LabeledStatementTree>();
	private Map<LabeledStatementTree, PendingGotos> gotoInstructions = new HashMap<>();
	private List<StatementTree> statementsInSequence = new LinkedList<>();

	/**
	 * Keep track of a goto statement
	 * @param stat
	 */
	public void recordGoto(final GotoTree stat) {
		gotos.put(stat, stat.getLabel());
		statementsInSequence.add(stat);
	}

	/**
	 * Keep track of a potential goto target
	 * @param stat
	 */
	public void recordTarget(final LabeledStatementTree stat) {
		targets.put(stat.getLabel(), stat);
		statementsInSequence.add(stat);
	}

	/**
	 * Whether any goto has label as target
	 * @param stat
	 * @return
	 */
	public boolean isUsed(LabeledStatementTree stat) {
		return gotos.values().contains(stat.getLabel());
	}

	/**
	 * Structure for keeping track of gotos byte codes, where we still do not know the target address
	 * @author marska
	 *
	 */
	private class PendingGotos {
		Collection<Chain> chains;
		int targetPc;
		Code emitter;

		void process(final Code emitter) {
			this.emitter = emitter;
			this.targetPc = emitter.cp;
			for (final Chain chain : this.chains) {
				emitter.resolve(chain, this.targetPc);
			}
		}

		void addChain(final Chain chain) {
			if (this.emitter != null) {// statement env already populated: add
										// chain
				if (!emitter.isAlive())// bring Code back to life if
					emitter.entryPoint();
				emitter.resolve(chain, targetPc);
			} else { // add chain for later processing
				this.chains.add(chain);
			}
		}

	}

	/**
	 * 
	 * @param link
	 * @return Reference to the labeled statement that this goto points to
	 */
	public LabeledStatementTree findTarget(final GotoTree link) {
		return targets.get(gotos.get(link));
	}

	/**
	 * 
	 * @param chain
	 *            - the branch pointing to the statement
	 * @param target
	 *            - target statement of gotos
	 */
	public void addBranch(final Chain chain, final LabeledStatementTree target) {
		getPendingGotos(target).addChain(chain);

	}

	/**
	 * Find pending gotos that point to myself, that should be patched now that the location is known
	 * @param target
	 * @return
	 */
	private PendingGotos getPendingGotos(final LabeledStatementTree target) {
		PendingGotos gotos = gotoInstructions.get(target);
		if (gotos == null) { // first reference to statement, populate with
								// structure
			gotos = new PendingGotos();
			gotoInstructions.put(target, gotos);
			gotos.chains = new LinkedList<>();

		}
		return gotos;
	}

	/**
	 * Process any pending chains for a given context now that we have it
	 * 
	 * @param target
	 *            - statement gotos point to
	 * @param context
	 *            - the context for the statement
	 */
	public void process(final LabeledStatementTree target, final Code emitter) {
		getPendingGotos(target).process(emitter);

	}

	/**
	 * Detect circular goto sequence, returning start position of such statement
	 * or 0 if none
	 * 
	 * @param start
	 * @return
	 */
	public int detectCircularGotoPosition(final GotoTree start) {
		final Collection<StatementTree> recursionSet = new LinkedList<StatementTree>();
		

		StatementTree currentNode = statementsInSequence.get(0);
		while (currentNode != null) {
			// if the set contains node we have been here before
			if (recursionSet.contains(currentNode)) {
				return startPosition(currentNode);
			}

			if (currentNode instanceof GotoTree) // follow gotos, record source
													// and target in recursion
													// set
			{
				recursionSet.add(currentNode);
				currentNode = findTarget((GotoTree) currentNode);
			} else if (statementsInSequence.indexOf(currentNode) < statementsInSequence
					.size() - 1)// proceed to next statement
				currentNode = statementsInSequence.get(statementsInSequence
						.indexOf(currentNode) + 1);
			else
				currentNode = null;

		}
		return -1;
	}

	/**
	 * 
	 * @param tree
	 * @return offset position of beginning of tree
	 */
	private static int startPosition(StatementTree tree) {
		return TreeInfo.getStartPos((JCTree) tree);
	}

}
