///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.IKnowledge;
import edu.cmu.tetrad.data.Knowledge2;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.util.ChoiceGenerator;
import edu.cmu.tetrad.util.StatUtils;
import edu.cmu.tetrad.util.TetradLogger;

import java.io.PrintStream;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the "fast adjacency search" used in several causal algorithm in this package. In the fast adjacency
 * search, at a given stage of the search, an edge X*-*Y is removed from the graph if X _||_ Y | S, where S is a subset
 * of size d either of adj(X) or of adj(Y), where d is the depth of the search. The fast adjacency search performs this
 * procedure for each pair of adjacent edges in the graph and for each depth d = 0, 1, 2, ..., d1, where d1 is either
 * the maximum depth or else the first such depth at which no edges can be removed. The interpretation of this adjacency
 * search is different for different algorithm, depending on the assumptions of the algorithm. A mapping from {x, y} to
 * S({x, y}) is returned for edges x *-* y that have been removed.
 *
 * @author Joseph Ramsey.
 */
public class FasStableLiAndWang implements IFas {

    /**
     * The search graph. It is assumed going in that all of the true adjacencies of x are in this graph for every node
     * x. It is hoped (i.e. true in the large sample limit) that true adjacencies are never removed.
     */
    private Graph graph;

    /**
     * The independence test. This should be appropriate to the types
     */
    private IndependenceTest test;

    /**
     * Specification of which edges are forbidden or required.
     */
    private IKnowledge knowledge = new Knowledge2();

    /**
     * The maximum number of variables conditioned on in any conditional independence test. If the depth is -1, it will
     * be taken to be the maximum value, which is 1000. Otherwise, it should be set to a non-negative integer.
     */
    private int depth = 1000;

    /**
     * The number of independence tests.
     */
    private int numIndependenceTests;


    /**
     * The logger, by default the empty logger.
     */
    private TetradLogger logger = TetradLogger.getInstance();

    /**
     * The true graph, for purposes of comparison. Temporary.
     */
    private Graph trueGraph;

    /**
     * The number of false dependence judgements, judged from the true graph using d-separation. Temporary.
     */
    private int numFalseDependenceJudgments;

    /**
     * The sepsets found during the search.
     */
    private SepsetMap sepset = new SepsetMap();

    /**
     * True if this is being run by FCI--need to skip the knowledge forbid step.
     */
    private boolean fci = false;

    /**
     * The depth 0 graph, specified initially.
     */
    private Graph initialGraph;

    private NumberFormat nf = new DecimalFormat("0.00E0");

    /**
     * True iff verbose output should be printed.
     */
    private boolean verbose = false;

    private PrintStream out = System.out;

    private List<Double> pMax = new ArrayList<>();

    //==========================CONSTRUCTORS=============================//

    /**
     * Constructs a new FastAdjacencySearch.
     */
    public FasStableLiAndWang(Graph graph, IndependenceTest test) {
        this.graph = graph;
        this.test = test;
    }

    public FasStableLiAndWang(IndependenceTest test) {
        this.test = test;
    }

    //==========================PUBLIC METHODS===========================//

    /**
     * Discovers all adjacencies in data.  The procedure is to remove edges in the graph which connect pairs of
     * variables which are independent conditional on some other set of variables in the graph (the "sepset"). These are
     * removed in tiers.  First, edges which are independent conditional on zero other variables are removed, then edges
     * which are independent conditional on one other variable are removed, then two, then three, and so on, until no
     * more edges can be removed from the graph.  The edges which remain in the graph after this procedure are the
     * adjacencies in the data.
     *
     * @return a SepSet, which indicates which variables are independent conditional on which other variables
     */
    public Graph search() {
        this.logger.log("info", "Starting Fast Adjacency Search.");

        if (graph == null) graph = new EdgeListGraphSingleConnections(test.getVariables());
        graph.removeEdges(graph.getEdges());

        sepset = new SepsetMap();

        int _depth = depth;

        if (_depth == -1) {
            _depth = 1000;
        }

        Map<Node, Set<Node>> adjacencies = new ConcurrentHashMap<>();
        List<Node> nodes = graph.getNodes();

        for (Node node : nodes) {
            adjacencies.put(node, new TreeSet<Node>());

            for (Node y : nodes) {
                if (node == y) continue;
                adjacencies.get(node).add(y);
            }
        }


        pMax = new ArrayList<>();

        for (int i = 0; i < nodes.size() * (nodes.size() - 1) / 2; i++) {
            pMax.add(-1.0);
        }

        for (int d = 0; d <= _depth; d++) {
            boolean more;

            if (d == 0) {
                more = searchAtDepth0(nodes, test, adjacencies);
            } else {
                more = searchAtDepth(nodes, test, adjacencies, d);
            }

            if (!more) {
                break;
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Node x = nodes.get(i);
                Node y = nodes.get(j);

                if (adjacencies.get(x).contains(y) && !graph.isAdjacentTo(x, y)) {
                    graph.addUndirectedEdge(x, y);
                }
            }
        }

//        GraphUtils.checkMarkov(graph, test, depth);

        this.logger.log("info", "Finishing Fast Adjacency Search.");

        return graph;
    }

    public Map<Node, Set<Node>> searchMapOnly() {
        this.logger.log("info", "Starting Fast Adjacency Search.");
        graph.removeEdges(graph.getEdges());

        sepset = new SepsetMap();

        int _depth = depth;

        if (_depth == -1) {
            _depth = 1000;
        }


        Map<Node, Set<Node>> adjacencies = new ConcurrentHashMap<>();
        List<Node> nodes = graph.getNodes();

        for (Node node : nodes) {
            adjacencies.put(node, new TreeSet<Node>());
        }

        for (int d = 0; d <= 0; d++) {//_depth; d++) {
            boolean more;

            if (d == 0) {
                more = searchAtDepth0(nodes, test, adjacencies);
            } else {
                more = searchAtDepth(nodes, test, adjacencies, d);
            }

            if (!more) {
                break;
            }
        }

        return adjacencies;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        if (depth < -1) {
            throw new IllegalArgumentException(
                    "Depth must be -1 (unlimited) or >= 0.");
        }

        this.depth = depth;
    }

    public IKnowledge getKnowledge() {
        return knowledge;
    }

    public void setKnowledge(IKnowledge knowledge) {
        if (knowledge == null) {
            throw new NullPointerException("Cannot set knowledge to null");
        }
        this.knowledge = knowledge;
    }

    //==============================PRIVATE METHODS======================/

    private boolean searchAtDepth0(List<Node> nodes, IndependenceTest test, Map<Node, Set<Node>> adjacencies) {
        List<Node> empty = Collections.emptyList();
        for (int i = 0; i < nodes.size(); i++) {
            if (verbose) {
                if ((i + 1) % 100 == 0) out.println("Node # " + (i + 1));
            }

            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            Node x = nodes.get(i);

            for (int j = i + 1; j < nodes.size(); j++) {

                Node y = nodes.get(j);

                if (initialGraph != null) {
                    Node x2 = initialGraph.getNode(x.getName());
                    Node y2 = initialGraph.getNode(y.getName());

                    if (!initialGraph.isAdjacentTo(x2, y2)) {
                        continue;
                    }
                }

                double p;

                try {
                    test.isIndependent(x, y, empty);
                    p = test.getPValue();
                } catch (Exception e) {
                    p = 0.0;
                }

                remove(nodes, test, adjacencies, empty, x, y, p);
            }
        }

        _p = new ArrayList<>(pMax);

        return freeDegree(nodes, adjacencies) > 0;
    }

    private boolean searchAtDepth(List<Node> nodes, final IndependenceTest test, Map<Node, Set<Node>> adjacencies, int depth) {
        int count = 0;

        final Map<Node, Set<Node>> adjacenciesCopy = new ConcurrentHashMap<>();

        for (Node node : adjacencies.keySet()) {
            adjacenciesCopy.put(node, new HashSet<>(adjacencies.get(node)));
        }

        for (Node x : nodes) {
            if (verbose) {
                if (++count % 100 == 0) out.println("count " + count + " of " + nodes.size());
            }

            List<Node> adjx = new ArrayList<>(adjacenciesCopy.get(x));

            EDGE:
            for (Node y : adjx) {
                List<Node> _adjx = new ArrayList<>(adjx);
                _adjx.remove(y);
                List<Node> ppx = possibleParents(x, _adjx, knowledge);

                if (ppx.size() >= depth) {
                    ChoiceGenerator cg = new ChoiceGenerator(ppx.size(), depth);
                    int[] choice;

                    while ((choice = cg.next()) != null) {
                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }

                        List<Node> condSet = GraphUtils.asList(choice, ppx);

                        double p;

                        try {
                            test.isIndependent(x, y, condSet);
                            p = test.getPValue();
                        } catch (Exception e) {
                            p = 0.0;
                        }

                        remove(nodes, test, adjacencies, condSet, x, y, p);
                    }
                }
            }
        }

        return freeDegree(nodes, adjacencies) > depth;
    }

    private void remove(List<Node> nodes, IndependenceTest test, Map<Node, Set<Node>> adjacencies, List<Node> empty, Node x, Node y, double p) {
        double cutoff = putPmax(x, y, p, graph);

        if (cutoff != -1) {
            for (NodePair pair : myIndices.keySet()) {
                Node _x = pair.getFirst();
                Node _y = pair.getSecond();

                if (getPMax(_x, _y, graph) > cutoff) {
                    adjacencies.get(_x).remove(_y);
                    adjacencies.get(_y).remove(_x);

                    getSepsets().set(_x, _y, empty);

                    if (verbose) {
                        TetradLogger.getInstance().forceLogMessage(SearchLogUtils.independenceFact(x, y, empty) + " p = " +
                                nf.format(test.getPValue()));
                        out.println(SearchLogUtils.independenceFactMsg(x, y, empty, test.getPValue()));
                    }
                }
            }
        }
    }

    double cutoff = Double.POSITIVE_INFINITY;

    List<Double> _p = new ArrayList<>();

    private double putPmax(Node x, Node y, double p, Graph graph) {
        int i = myIndex(x, y, graph);

        final Double pOld = pMax.get(i);

        if (p > pOld) {
            pMax.set(i, p);

            _p.remove(pOld);
            _p.add(p);

            for (Double aPMax : pMax) {
                if (aPMax == -1) {
                    return -1;
                }
            }

            double _cutoff = StatUtils.fdrCutoff(test.getAlpha(), _p, true, false);

            if (_cutoff < cutoff) {
                cutoff = _cutoff;
                return _cutoff;
            }
        }

        return -1;
    }

    private double getPMax(Node x, Node y, Graph graph) {
        int i = myIndex(x, y, graph);
        return pMax.get(i);
    }

    Map<NodePair, Integer> myIndices = null;

    private int myIndex(Node x, Node y, Graph graph) {
        if (myIndices == null) {
            myIndices = new HashMap<>();
            List<Node> nodes = graph.getNodes();
            int count = 0;

            for (int i = 0; i < nodes.size(); i++) {
                for (int j = i + 1; j < nodes.size(); j++) {
                    myIndices.put(new NodePair(nodes.get(i), nodes.get(j)), count++);
                }
            }
        }


        return myIndices.get(new NodePair(x, y));
    }

    private int freeDegree(List<Node> nodes, Map<Node, Set<Node>> adjacencies) {
        int max = 0;

        for (Node x : nodes) {
            Set<Node> opposites = adjacencies.get(x);

            for (Node y : opposites) {
                Set<Node> adjx = new HashSet<>(opposites);
                adjx.remove(y);

                if (adjx.size() > max) {
                    max = adjx.size();
                }
            }
        }

        return max;
    }

    private boolean forbiddenEdge(Node x, Node y) {
        String name1 = x.getName();
        String name2 = y.getName();

        if (knowledge.isForbidden(name1, name2) &&
                knowledge.isForbidden(name2, name1)) {
            this.logger.log("edgeRemoved", "Removed " + Edges.undirectedEdge(x, y) + " because it was " +
                    "forbidden by background knowledge.");

            return true;
        }

        return false;
    }


    private List<Node> possibleParents(Node x, List<Node> adjx,
                                       IKnowledge knowledge) {
        List<Node> possibleParents = new LinkedList<>();
        String _x = x.getName();

        for (Node z : adjx) {
            String _z = z.getName();

            if (possibleParentOf(_z, _x, knowledge)) {
                possibleParents.add(z);
            }
        }

        return possibleParents;
    }

    private boolean possibleParentOf(String z, String x, IKnowledge knowledge) {
        return !knowledge.isForbidden(z, x) && !knowledge.isRequired(x, z);
    }

    public int getNumIndependenceTests() {
        return numIndependenceTests;
    }

    public void setTrueGraph(Graph trueGraph) {
        this.trueGraph = trueGraph;
    }

    public int getNumFalseDependenceJudgments() {
        return numFalseDependenceJudgments;
    }

    public int getNumDependenceJudgments() {
        return -1;
    }

    public SepsetMap getSepsets() {
        return sepset;
    }

    public void setInitialGraph(Graph initialGraph) {
        this.initialGraph = initialGraph;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public boolean isAggressivelyPreventCycles() {
        return false;
    }

    @Override
    public void setAggressivelyPreventCycles(boolean aggressivelyPreventCycles) {

    }

    @Override
    public IndependenceTest getIndependenceTest() {
        return null;
    }

    @Override
    public Graph search(List<Node> nodes) {
        return null;
    }

    @Override
    public long getElapsedTime() {
        return 0;
    }

    @Override
    public List<Node> getNodes() {
        return test.getVariables();
    }

    @Override
    public List<Triple> getAmbiguousTriples(Node node) {
        return null;
    }

    @Override
    public void setOut(PrintStream out) {
        this.out = out;
    }
}
