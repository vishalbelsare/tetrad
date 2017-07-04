package edu.cmu.tetrad.algcomparison.algorithm.external;

import edu.cmu.tetrad.algcomparison.algorithm.Algorithm;
import edu.cmu.tetrad.algcomparison.algorithm.ExternalAlgorithm;
import edu.cmu.tetrad.algcomparison.simulation.Simulation;
import edu.cmu.tetrad.data.DataModel;
import edu.cmu.tetrad.data.DataReader;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.data.DataType;
import edu.cmu.tetrad.graph.EdgeListGraph;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.graph.GraphUtils;
import edu.cmu.tetrad.search.SearchGraphUtils;
import edu.cmu.tetrad.util.Parameters;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * An API to allow results from external algorithms to be included in a report through the algrorithm
 * comparison tool. This one is for matrix generated by PC in pcalg. See below. This script can generate
 * the files in R.
 *
 library("MASS");
 library("pcalg");

 path<-"/Users/user/tetrad/comparison/save";
 subdir<-"rgraph";
 simulation<-1;

 dir.create(paste(path, "/", simulation, "/", subdir, sep=""));

 for (i in 1:10) {
 data<-read.table(paste(path, "/", simulation, "/data/data.1.txt", sep=""), header=TRUE)
 n<-nrow(data)
 C<-cor(data)
 v<-names(data)
 suffStat<-list(C = C, n=n)
 pc.fit<-pc(suffStat=suffStat, indepTest=gaussCItest, alpha=0.001, labels=v,
 skel.method="stable")
 A<-as(pc.fit, "amat")
 name<-paste(path, "/", simulation, "/", subdir, "/graph.", i, ".txt", sep="")
 print(name)
 write.matrix(A, file=name, sep="\t")
 }
 * @author jdramsey
 */
public class ExternalAlgorithmPcalgPc implements ExternalAlgorithm {
    static final long serialVersionUID = 23L;
    private final String extDir;
    private String path;
    private List<String> usedParameters = new ArrayList<>();
    private Simulation simulation;

    /**
     *
     * @param extDir The name of the directory (within each simulation) that the externally generated graphs
     *               will be stored. These need to be generated by the external algorithm and placed there.
     *               The format will vary with the particular version of this class used. This one is for
     *               R graphs generated by the PC algorithm in pcalg. For these graphs, there is a row of
     *               n variable names separated by tabs, followed by an n x n matrix M of 0's and 1's. If
     *               M[i][j] = M[j][i] =1, then an edge i--j is added to the graph. if this is not true but
     *               M[i][j] = 1, then i->j is added to the graph. If neither of the above is true but
     *               M[j][i] = 1, then j->i is added to the graph. These matrices should be in filed named
     *               graph.1.txt, graph.2.txt, etc., in the directory save/1/[name] for as many datasets as
     *               there are in save/1/data as saved out using the API (see algcomparison/examples/ExampleSave.
     */
    public ExternalAlgorithmPcalgPc(String extDir) {
        this.extDir = extDir;
    }

    @Override
    /**
     * Reads in the relevant graph from the file (see above) and returns it.
     */
    public Graph search(DataModel dataSet, Parameters parameters) {
        int index = -1;

        try {

            for (int i = 0; i < getNumDataModels(); i++) {
                if (dataSet == simulation.getDataModel(i)) {
                    index = i + 1;
                    break;
                }
            }

            if (index == -1) {
                throw new IllegalArgumentException("Not a dataset for this simulation.");
            }

            DataReader reader = new DataReader();
            reader.setVariablesSupplied(true);
            File file3 = new File(path, extDir +"/graph." + index + ".txt");
            DataSet dataSet2 = reader.parseTabular(file3);
            System.out.println("Loading graph from " + file3.getAbsolutePath());
            Graph graph = GraphUtils.loadGraphPcAlgMatrix(dataSet2);
            GraphUtils.circleLayout(graph, 225, 200, 150);

            return graph;
        } catch (IOException e) {
            e.printStackTrace();
        }

        throw new IllegalArgumentException("Couldn't find a graph at a " + path + "/" + extDir + "/graph." + index + ".txt");
    }

    @Override
    /**
     * Returns the patter of the supplied DAG.
     */
    public Graph getComparisonGraph(Graph graph) {
        Graph graph1 = SearchGraphUtils.patternForDag(new EdgeListGraph(graph));

        System.out.println("Comparison graph" + graph1);

        return graph1;
    }

    public String getDescription() {
        return "Load data from " + path + "/" + extDir;
    }

    @Override
    public List<String> getParameters() {
        return usedParameters;
    }

    public int getNumDataModels() {
        return simulation.getNumDataModels();
    }

    @Override
    public DataType getDataType() {
        return DataType.Continuous;
    }

    public void setSimulation(Simulation simulation) {
        this.simulation = simulation;
    }

    public void setPath(String path) {
        this.path = path;
    }

}