package mltk.predictor.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import mltk.cmdline.Argument;
import mltk.cmdline.CmdLineParser;
import mltk.core.Attribute;
import mltk.core.BinnedAttribute;
import mltk.core.Instance;
import mltk.core.Instances;
import mltk.core.NominalAttribute;
import mltk.core.Attribute.Type;
import mltk.core.io.InstancesReader;
import mltk.predictor.Bagging;
import mltk.predictor.Learner;
import mltk.predictor.io.PredictorWriter;
import mltk.util.Random;
import mltk.util.Stack;
import mltk.util.Element;
import mltk.util.tuple.DoublePair;
import mltk.util.tuple.IntDoublePair;

/**
 * Class for learning regression trees.
 * 
 * @author Yin Lou
 * 
 */
public class RegressionTreeLearner extends Learner {

	protected int maxDepth;
	protected int maxNumLeaves;
	protected double alpha;
	protected Mode mode;

	/**
	 * Enumeration of construction mode.
	 * 
	 * @author Yin Lou
	 * 
	 */
	public enum Mode {

		DEPTH_LIMITED, NUM_LEAVES_LIMITED, ALPHA_LIMITED;
	}

	/**
	 * Constructor.
	 */
	public RegressionTreeLearner() {
		alpha = 0.01;
		mode = Mode.ALPHA_LIMITED;
	}

	/**
	 * Returns the alpha.
	 * 
	 * @return the alpha.
	 */
	public double getAlpha() {
		return alpha;
	}

	/**
	 * Sets the alpha. Alpha is the maximum proportion of the training set in
	 * the leaf node.
	 * 
	 * @param alpha
	 *            the alpha.
	 */
	public void setAlpha(double alpha) {
		this.alpha = alpha;
	}

	/**
	 * Returns the maximum number of leaves.
	 * 
	 * @return the maximum number of leaves.
	 */
	public int getMaxNumLeaves() {
		return maxNumLeaves;
	}

	/**
	 * Sets the maximum number of leaves.
	 * 
	 * @param maxNumLeaves
	 *            the maximum number of leaves.
	 */
	public void setMaxNumLeaves(int maxNumLeaves) {
		this.maxNumLeaves = maxNumLeaves;
	}

	/**
	 * Returns the maximum depth.
	 * 
	 * @return the maximum depth.
	 */
	public int getMaxDepth() {
		return maxDepth;
	}

	/**
	 * Sets the maximum depth.
	 * 
	 * @param maxDepth
	 *            the maximum depth.
	 */
	public void setMaxDepth(int maxDepth) {
		this.maxDepth = maxDepth;
	}

	/**
	 * Returns the construction mode.
	 * 
	 * @return the construction mode.
	 */
	public Mode getConstructionMode() {
		return mode;
	}

	/**
	 * Sets the construction mode.
	 * 
	 * @param mode
	 *            the construction mode.
	 */
	public void setConstructionMode(Mode mode) {
		this.mode = mode;
	}

	@Override
	public RegressionTree build(Instances instances) {
		RegressionTree rt = null;
		switch (mode) {
		case ALPHA_LIMITED:
			rt = buildAlphaLimitedTree(instances, alpha);
			break;
		case NUM_LEAVES_LIMITED:
			rt = buildNumLeafLimitedTree(instances, maxNumLeaves);
			break;
		case DEPTH_LIMITED:
			rt = buildDepthLimitedTree(instances, maxDepth);
			break;
		default:
			break;
		}
		return rt;
	}

	protected RegressionTree buildNumLeafLimitedTree(Instances instances,
			int maxNumLeaves) {
		RegressionTree tree = new RegressionTree();
		final int limit = 5;
		// stats[0]: totalWeights
		// stats[1]: weightedMean
		// stats[2]: splitEval
		double[] stats = new double[3];
		Map<RegressionTreeNode, Double> nodePred = new HashMap<>();
		Map<RegressionTreeNode, Dataset> datasets = new HashMap<>();
		Dataset dataset = Dataset.create(instances);
		PriorityQueue<Element<RegressionTreeNode>> q = new PriorityQueue<>();
		tree.root = createNode(dataset, limit, stats);
		q.add(new Element<RegressionTreeNode>(tree.root, stats[2]));
		datasets.put(tree.root, dataset);
		nodePred.put(tree.root, stats[1]);

		int numLeaves = 0;
		while (!q.isEmpty()) {
			Element<RegressionTreeNode> elemt = q.remove();
			RegressionTreeNode node = elemt.element;
			Dataset data = datasets.get(node);
			if (!node.isLeaf()) {
				RegressionTreeInteriorNode interiorNode = (RegressionTreeInteriorNode) node;
				Dataset left = new Dataset(data.instances);
				Dataset right = new Dataset(data.instances);
				data.split(interiorNode, left, right);

				interiorNode.left = createNode(left, limit, stats);
				if (!interiorNode.left.isLeaf()) {
					nodePred.put(interiorNode.left, stats[1]);
					q.add(new Element<RegressionTreeNode>(interiorNode.left,
							stats[2]));
					datasets.put(interiorNode.left, left);
				} else {
					numLeaves++;
				}
				interiorNode.right = createNode(right, limit, stats);
				if (!interiorNode.right.isLeaf()) {
					nodePred.put(interiorNode.right, stats[1]);
					q.add(new Element<RegressionTreeNode>(interiorNode.right,
							stats[2]));
					datasets.put(interiorNode.right, right);
				} else {
					numLeaves++;
				}

				if (numLeaves + q.size() >= maxNumLeaves) {
					break;
				}
			}
		}

		// Convert interior nodes to leaves
		Map<RegressionTreeNode, RegressionTreeNode> parent = new HashMap<>();
		traverse(tree.root, parent);
		while (!q.isEmpty()) {
			Element<RegressionTreeNode> elemt = q.remove();
			RegressionTreeNode node = elemt.element;

			double prediction = nodePred.get(node);
			RegressionTreeInteriorNode interiorNode = (RegressionTreeInteriorNode) parent
					.get(node);
			if (interiorNode.left == node) {
				interiorNode.left = new RegressionTreeLeaf(prediction);
			} else {
				interiorNode.right = new RegressionTreeLeaf(prediction);
			}
		}

		return tree;
	}

	protected RegressionTree buildDepthLimitedTree(Instances instances,
			int maxDepth) {
		RegressionTree tree = new RegressionTree();
		final int limit = 5;
		// stats[0]: totalWeights
		// stats[1]: weightedMean
		// stats[2]: splitEval
		double[] stats = new double[3];
		if (maxDepth == 1) {
			getStats(instances, stats);
			tree.root = new RegressionTreeLeaf(stats[1]);
			return tree;
		}
		Map<RegressionTreeNode, Dataset> datasets = new HashMap<>();
		Map<RegressionTreeNode, Integer> depths = new HashMap<>();
		Dataset dataset = Dataset.create(instances);
		tree.root = createNode(dataset, limit, stats);
		PriorityQueue<Element<RegressionTreeNode>> q = new PriorityQueue<>();
		q.add(new Element<RegressionTreeNode>(tree.root, stats[2]));
		datasets.put(tree.root, dataset);
		depths.put(tree.root, 1);

		while (!q.isEmpty()) {
			Element<RegressionTreeNode> elemt = q.remove();
			RegressionTreeNode node = elemt.element;
			Dataset data = datasets.get(node);
			int depth = depths.get(node);
			if (!node.isLeaf()) {
				RegressionTreeInteriorNode interiorNode = (RegressionTreeInteriorNode) node;
				Dataset left = new Dataset(data.instances);
				Dataset right = new Dataset(data.instances);
				data.split(interiorNode, left, right);

				if (depth + 1 == maxDepth) {
					getStats(left.instances, stats);
					interiorNode.left = new RegressionTreeLeaf(stats[1]);
					getStats(right.instances, stats);
					interiorNode.right = new RegressionTreeLeaf(stats[1]);
				} else {
					interiorNode.left = createNode(left, limit, stats);
					if (!interiorNode.left.isLeaf()) {
						q.add(new Element<RegressionTreeNode>(
								interiorNode.left, stats[2]));
						datasets.put(interiorNode.left, left);
						depths.put(interiorNode.left, depth + 1);
					}
					interiorNode.right = createNode(right, limit, stats);
					if (!interiorNode.right.isLeaf()) {
						q.add(new Element<RegressionTreeNode>(
								interiorNode.right, stats[2]));
						datasets.put(interiorNode.right, right);
						depths.put(interiorNode.right, depth + 1);
					}
				}
			}
		}

		return tree;
	}

	protected RegressionTree buildAlphaLimitedTree(Instances instances,
			double alpha) {
		RegressionTree tree = new RegressionTree();
		double[] stats = new double[3];
		Dataset dataset = Dataset.create(instances);
		Stack<RegressionTreeNode> nodes = new Stack<>();
		Stack<Dataset> datasets = new Stack<>();
		final int limit = (int) (alpha * instances.size());
		tree.root = createNode(dataset, limit, stats);
		nodes.push(tree.root);
		datasets.push(dataset);
		while (!nodes.isEmpty()) {
			RegressionTreeNode node = nodes.pop();
			Dataset data = datasets.pop();
			if (!node.isLeaf()) {
				RegressionTreeInteriorNode interiorNode = (RegressionTreeInteriorNode) node;
				Dataset left = new Dataset(data.instances);
				Dataset right = new Dataset(data.instances);
				data.split(interiorNode, left, right);
				interiorNode.left = createNode(left, limit, stats);
				interiorNode.right = createNode(right, limit, stats);
				nodes.push(interiorNode.left);
				datasets.push(left);
				nodes.push(interiorNode.right);
				datasets.push(right);
			}
		}
		return tree;
	}

	protected void traverse(RegressionTreeNode node,
			Map<RegressionTreeNode, RegressionTreeNode> parent) {
		if (!node.isLeaf()) {
			RegressionTreeInteriorNode interiorNode = (RegressionTreeInteriorNode) node;
			if (interiorNode.left != null) {
				parent.put(interiorNode.left, node);
				traverse(interiorNode.left, parent);
			}
			if (interiorNode.right != null) {
				parent.put(interiorNode.right, node);
				traverse(interiorNode.right, parent);
			}
		}
	}

	protected boolean getStats(Instances instances, double[] stats) {
		stats[0] = stats[1] = 0;
		if (instances.size() == 0) {
			return true;
		}
		double firstTarget = instances.get(0).getTarget();
		boolean stdIs0 = true;
		for (Instance instance : instances) {
			double weight = instance.getWeight();
			double target = instance.getTarget();
			stats[0] += weight;
			stats[1] += weight * target;
			if (stdIs0 && target != firstTarget) {
				stdIs0 = false;
			}
		}
		stats[1] /= stats[0];
		return stdIs0;
	}

	protected RegressionTreeNode createNode(Dataset dataset, int limit,
			double[] stats) {
		boolean stdIs0 = getStats(dataset.instances, stats);
		final double totalWeights = stats[0];
		final double weightedMean = stats[1];
		final double sum = totalWeights * weightedMean;

		// 1. Check basic leaf conditions
		if (stats[0] < limit || stdIs0) {
			RegressionTreeNode node = new RegressionTreeLeaf(weightedMean);
			return node;
		}

		// 2. Find best split
		double bestEval = Double.POSITIVE_INFINITY;
		List<IntDoublePair> splits = new ArrayList<>();
		List<Attribute> attributes = dataset.instances.getAttributes();
		for (int i = 0; i < attributes.size(); i++) {
			Attribute attribute = attributes.get(i);
			int attIndex = attribute.getIndex();
			List<Double> uniqueValues = null;
			List<DoublePair> histogram = null;
			if (attribute.getType() == Type.NOMINAL) {
				NominalAttribute attr = (NominalAttribute) attribute;
				DoublePair[] hist = new DoublePair[attr.getCardinality()];
				for (int j = 0; j < hist.length; j++) {
					hist[j] = new DoublePair(0, 0);
				}
				for (Instance instance : dataset.instances) {
					int idx = (int) instance.getValue(attIndex);
					hist[idx].v2 += instance.getTarget() * instance.getWeight();
					hist[idx].v1 += instance.getWeight();
				}

				uniqueValues = new ArrayList<>(hist.length);
				histogram = new ArrayList<>(hist.length);
				for (int j = 0; j < hist.length; j++) {
					if (hist[j].v1 != 0) {
						histogram.add(hist[j]);
						uniqueValues.add((double) j);
					}
				}
			} else if (attribute.getType() == Type.BINNED) {
				BinnedAttribute attr = (BinnedAttribute) attribute;
				DoublePair[] hist = new DoublePair[attr.getNumBins()];
				for (int j = 0; j < hist.length; j++) {
					hist[j] = new DoublePair(0, 0);
				}
				for (Instance instance : dataset.instances) {
					int idx = (int) instance.getValue(attIndex);
					hist[idx].v2 += instance.getTarget() * instance.getWeight();
					hist[idx].v1 += instance.getWeight();
				}

				uniqueValues = new ArrayList<>(hist.length);
				histogram = new ArrayList<>(hist.length);
				for (int j = 0; j < hist.length; j++) {
					if (hist[j].v1 != 0) {
						histogram.add(hist[j]);
						uniqueValues.add((double) j);
					}
				}
			} else {
				List<IntDoublePair> sortedList = dataset.sortedLists.get(i);
				int capacity = dataset.instances.size();
				uniqueValues = new ArrayList<>(capacity);
				histogram = new ArrayList<>(capacity);
				getHistogram(dataset.instances, sortedList, uniqueValues,
						histogram);
				System.out.println("Histogram for " + attribute.getName());
				for (int k = 0; k < uniqueValues.size(); k++) {
					DoublePair pair = histogram.get(k);
					System.out.println(uniqueValues.get(k) + " " + pair.v1
							+ " " + pair.v2);
				}
			}

			if (uniqueValues.size() > 1) {
				DoublePair split = split(uniqueValues, histogram, totalWeights,
						sum);
				if (split.v2 <= bestEval) {
					IntDoublePair splitPoint = new IntDoublePair(attIndex,
							split.v1);
					if (split.v2 < bestEval) {
						splits.clear();
						bestEval = split.v2;
					}
					splits.add(splitPoint);
				}
			}
		}
		if (bestEval < Double.POSITIVE_INFINITY) {
			Random rand = Random.getInstance();
			IntDoublePair splitPoint = splits.get(rand.nextInt(splits.size()));
			int attIndex = splitPoint.v1;
			RegressionTreeNode node = new RegressionTreeInteriorNode(attIndex,
					splitPoint.v2);
			if (stats.length > 2) {
				stats[2] = bestEval + totalWeights * weightedMean
						* weightedMean;
			}
			return node;
		} else {
			RegressionTreeNode node = new RegressionTreeLeaf(weightedMean);
			return node;
		}
	}

	protected DoublePair split(List<Double> uniqueValues,
			List<DoublePair> stats, double totalWeights, double sum) {
		double weight1 = stats.get(0).v1;
		double weight2 = totalWeights - weight1;
		double sum1 = stats.get(0).v2;
		double sum2 = sum - sum1;

		double bestEval = -sum1 * sum1 / weight1 - sum2 * sum2 / weight2;
		List<Double> splits = new ArrayList<>();
		splits.add((uniqueValues.get(0) + uniqueValues.get(0 + 1)) / 2);
		for (int i = 1; i < uniqueValues.size() - 1; i++) {
			final double w = stats.get(i).v1;
			final double s = stats.get(i).v2;
			weight1 += w;
			weight2 -= w;
			sum1 += s;
			sum2 -= s;
			double eval = -sum1 * sum1 / weight1 - sum2 * sum2 / weight2;
			if (eval <= bestEval) {
				double split = (uniqueValues.get(i) + uniqueValues.get(i + 1)) / 2;
				if (eval < bestEval) {
					bestEval = eval;
					splits.clear();
				}
				splits.add(split);
			}
		}
		Random rand = Random.getInstance();
		double split = splits.get(rand.nextInt(splits.size()));
		return new DoublePair(split, bestEval);
	}

	protected void getHistogram(Instances instances, List<IntDoublePair> pairs,
			List<Double> uniqueValues, List<DoublePair> histogram) {
		if (pairs.size() == 0) {
			return;
		}
		double lastValue = pairs.get(0).v2;
		double totalWeight = instances.get(pairs.get(0).v1).getWeight();
		double sum = instances.get(pairs.get(0).v1).getTarget() * totalWeight;
		double lastResp = instances.get(pairs.get(0).v1).getTarget();
		boolean isStd0 = true;
		for (int i = 1; i < pairs.size(); i++) {
			IntDoublePair pair = pairs.get(i);
			double value = pair.v2;
			double weight = instances.get(pairs.get(i).v1).getWeight();
			double resp = instances.get(pairs.get(i).v1).getTarget();
			if (value != lastValue) {
				uniqueValues.add(lastValue);
				histogram.add(new DoublePair(totalWeight, sum));
				lastValue = value;
				totalWeight = weight;
				sum = resp * weight;
				lastResp = resp;
				isStd0 = true;
			} else {
				totalWeight += weight;
				sum += resp * weight;
				isStd0 = isStd0 && (lastResp == resp);
			}
		}
		uniqueValues.add(lastValue);
		histogram.add(new DoublePair(totalWeight, sum));
	}

	static class Options {

		@Argument(name = "-r", description = "attribute file path", required = true)
		String attPath = null;

		@Argument(name = "-t", description = "train set path", required = true)
		String trainPath = null;

		@Argument(name = "-o", description = "output model path")
		String outputModelPath = null;

		@Argument(name = "-m", description = "construction mode:parameter. Construction mode can be alpha limited (a), depth limited (d), and number of leaves limited (l) (default: a:0.01)")
		String mode = "a:0.01";

		@Argument(name = "-s", description = "seed of the random number generator (default: 0)")
		long seed = 0L;

	}

	/**
	 * Trains a regression tree.
	 * 
	 * <p>
	 * 
	 * <pre>
	 * Usage: RegressionTreeLearner
	 * -r	attribute file path
	 * -t	train set path
	 * [-o]	output model path
	 * [-m]	construction mode:parameter. Construction mode can be alpha limited (a), depth limited (d), and number of leaves limited (l) (default: a:0.001)
	 * </pre>
	 * 
	 * </p>
	 * 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		Options opts = new Options();
		CmdLineParser parser = new CmdLineParser(RegressionTreeLearner.class,
				opts);
		RegressionTreeLearner rtLearner = new RegressionTreeLearner();
		try {
			parser.parse(args);
			String[] data = opts.mode.split(":");
			if (data.length != 2) {
				throw new IllegalArgumentException();
			}
			switch (data[0]) {
			case "a":
				rtLearner.setConstructionMode(Mode.ALPHA_LIMITED);
				rtLearner.setAlpha(Double.parseDouble(data[1]));
				break;
			case "d":
				rtLearner.setConstructionMode(Mode.DEPTH_LIMITED);
				rtLearner.setMaxDepth(Integer.parseInt(data[1]));
				break;
			case "l":
				rtLearner.setConstructionMode(Mode.NUM_LEAVES_LIMITED);
				rtLearner.setMaxNumLeaves(Integer.parseInt(data[1]));
				break;
			default:
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException e) {
			parser.printUsage();
			System.exit(1);
		}

		Random.getInstance().setSeed(opts.seed);

		Instances trainSet = InstancesReader.read(opts.attPath, opts.trainPath);
		Instances bag = Bagging.createBootstrapSample(trainSet);
		long start = System.currentTimeMillis();
		RegressionTree rt = rtLearner.build(bag);
		long end = System.currentTimeMillis();
		System.out.println("Time: " + (end - start) / 1000.0 + " (s).");

		if (opts.outputModelPath != null) {
			PredictorWriter.write(rt, opts.outputModelPath);
		}
	}

}