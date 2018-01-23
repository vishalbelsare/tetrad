package edu.cmu.tetrad.algcomparison.algorithm;

import edu.cmu.tetrad.algcomparison.algorithm.oracle.pattern.Fges;
import edu.cmu.tetrad.annotation.AlgType;
import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.Edge;
import edu.cmu.tetrad.graph.EdgeListGraph;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.graph.Node;
import edu.cmu.tetrad.search.FgesMb;
import edu.cmu.tetrad.search.Ida;
import edu.cmu.tetrad.util.ForkJoinPoolInstance;
import edu.cmu.tetrad.util.Parameters;
import edu.cmu.tetrad.util.StatUtils;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

import static java.lang.Math.abs;

@edu.cmu.tetrad.annotation.Algorithm(
        name = "CStar",
        command = "cstar",
        algoType = AlgType.forbid_latent_common_causes,
        description = "Performs a CStar analysis of the given dataset (Stekhoven, Daniel J., et al. " +
                "Causal stability ranking.\" Bioinformatics 28.21 (2012): 2819-2823) and returns a graph " +
                "in which all selected variables are shown as into the target. The target is the first variables."
)
public class CStar implements Algorithm {

    static final long serialVersionUID = 23L;
    private Algorithm algorithm;
    private transient final ForkJoinPool pool;

    public CStar() {
        this(ForkJoinPoolInstance.getInstance().getPool());
    }

    public CStar(ForkJoinPool pool) {
        this.algorithm = new Fges();
        this.pool = new ForkJoinPool(10);
    }

    @Override
    public Graph search(DataModel dataSet, Parameters parameters) {
        DataSet _dataSet = (DataSet) dataSet;

        double percentSubsampleSize = parameters.getDouble("percentSubsampleSize");
        int numSubsamples = parameters.getInt("numSubsamples");
        int q = parameters.getInt("topQ");
        double pithreshold = parameters.getDouble("piThreshold");
        Node y = dataSet.getVariable(parameters.getString("targetName"));

        final List<Node> variables = dataSet.getVariables();
        variables.remove(y);

        Map<Node, Integer> counts = new ConcurrentHashMap<>();
        for (Node node : variables) counts.put(node, 0);

        class Task implements Callable<Boolean> {
            private int i;
            private Map<Node, Integer> counts;

            private Task(int i, Map<Node, Integer> counts) {
                this.i = i;
                this.counts = counts;
            }

            @Override
            public Boolean call() {
                BootstrapSampler sampler = new BootstrapSampler();
                sampler.setWithoutReplacements(true);
                DataSet sample = sampler.sample(_dataSet, (int) (percentSubsampleSize * _dataSet.getNumRows()));
                Ida ida = new Ida(sample);
                Ida.NodeEffects effects = ida.getSortedMinEffects(y);

                for (int i = 0; i < q; i++) {
                    final Node key = effects.getNodes().get(i);
                    counts.put(key, counts.get(key) + 1);
                }

                if (parameters.getBoolean("verbose")) {
                    System.out.println("Bootstrap #" + (i + 1) + " of " + numSubsamples);
                }

                return true;
            }
        }


        List<Task> tasks = new ArrayList<>();

        for (int i = 0; i < numSubsamples; i++) {
            tasks.add(new Task(i, counts));
        }

        pool.invokeAll(tasks);

        variables.sort((o1, o2) -> {
            final int d1 = counts.get(o1);
            final int d2 = counts.get(o2);
            return -Integer.compare(d1, d2);
        });

        double[] sortedFreqencies = new double[variables.size()];

        for (int i = 0; i < variables.size(); i++) {
            sortedFreqencies[i] = counts.get(variables.get(i));
        }

        for (int i = 0; i < sortedFreqencies.length; i++) {
            sortedFreqencies[i] /= (double) numSubsamples;
        }

        List<Node> outNodes = new ArrayList<>();

        for (int i = 0; i < variables.size(); i++) {
            if (sortedFreqencies[i] >= pithreshold) {
                outNodes.add(variables.get(i));
            }
        }

        Graph graph = new EdgeListGraph(outNodes);
        graph.addNode(y);

        for (int i = 0; i < new ArrayList<Node>(outNodes).size(); i++) {
            graph.addDirectedEdge(outNodes.get(i), y);
        }

        return graph;
    }

    @Override
    public Graph getComparisonGraph(Graph graph) {
        return algorithm.getComparisonGraph(graph);
    }

    @Override
    public String getDescription() {
        return "CStar";
    }

    @Override
    public DataType getDataType() {
        return DataType.Continuous;
    }

    @Override
    public List<String> getParameters() {
        List<String> parameters = new ArrayList<>();
        parameters.add("numSubsamples");
        parameters.add("percentSubsampleSize");
        parameters.add("topQ");
        parameters.add("piThreshold");
        parameters.add("targetName");
        return parameters;
    }
}
