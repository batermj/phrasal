package edu.stanford.nlp.mt.tune;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import edu.stanford.nlp.math.ArrayMath;
import edu.stanford.nlp.mt.Phrasal;
import edu.stanford.nlp.mt.base.FlatPhraseTable;
import edu.stanford.nlp.mt.base.IOTools;
import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.IStrings;
import edu.stanford.nlp.mt.base.RichTranslation;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.decoder.util.Scorer;
import edu.stanford.nlp.mt.decoder.util.StaticScorer;
import edu.stanford.nlp.mt.metrics.BLEUMetric;
import edu.stanford.nlp.mt.metrics.BLEUOracleCost;
import edu.stanford.nlp.mt.metrics.BLEUSmoothGain;
import edu.stanford.nlp.mt.metrics.SentenceLevelMetric;
import edu.stanford.nlp.mt.tune.optimizers.MIRA1BestHopeFearOptimizer;
import edu.stanford.nlp.mt.tune.optimizers.OnlineOptimizer;
import edu.stanford.nlp.mt.tune.optimizers.OnlineUpdateRule;
import edu.stanford.nlp.mt.tune.optimizers.OptimizerUtils;
import edu.stanford.nlp.mt.tune.optimizers.PairwiseRankingOptimizerSGD;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.stats.OpenAddressCounter;
import edu.stanford.nlp.stats.ThreadsafeCounter;
import edu.stanford.nlp.util.HashIndex;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.util.concurrent.MulticoreWrapper;
import edu.stanford.nlp.util.concurrent.ThreadsafeProcessor;

/**
 * Online model tuning for machine translation.
 * 
 * @author Spence Green
 *
 */
public class OnlineTuner {

  // TODO(spenceg): Move this logging stuff elsewhere.

  // Static methods for setting up a global logger
  // Other classes should attach() to this log handler
  private static Handler logHandler = null;
  private static String logPrefix;
  private static Level logLevel = Level.INFO;

  private static void initLogger(String tag) {
    // Disable default console logger
    Logger globalLogger = Logger.getLogger("global");
    Handler[] handlers = globalLogger.getHandlers();
    for(Handler handler : handlers) {
      globalLogger.removeHandler(handler);
    }

    // Setup the file logger
    logPrefix = tag + ".online";
    try {
      logHandler = new FileHandler(logPrefix + ".log");
      logHandler.setFormatter(new SimpleFormatter()); //Plain text
    } catch (SecurityException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void attach(Logger logger) {
    // Disable the console logger, then attach to the file logger.
    logger.setUseParentHandlers(false);
    if (logHandler != null) {
      logger.addHandler(logHandler);
    }
    logger.setLevel(logLevel);
  }

  // What it says
  private final Logger logger;

  // Intrinsic loss examples
  private List<Sequence<IString>> tuneSource;
  private List<List<Sequence<IString>>> references;

  // Various options
  private boolean writeNbestLists = false;
  private boolean returnBestDev = false;
  private boolean doParameterAveraging = false;
  
  // Weight vector for Phrasal
  private Counter<String> wts;
  private Index<String> featureIndex;
  private final int expectedNumFeatures;

  // The optimization algorithm
  private OnlineOptimizer<IString,String> optimizer;

  // Phrasal decoder instance.
  private Phrasal decoder;


  public OnlineTuner(String srcFile, String tgtFile, String phrasalIniFile, 
      String initialWtsFile, String optimizerAlg, String[] optimizerFlags, 
      boolean uniformStartWeights, boolean randomizeStartWeights, int expectedNumFeatures) {
    logger = Logger.getLogger(OnlineTuner.class.getName());
    OnlineTuner.attach(logger);

    // Configure the initial weights
    this.expectedNumFeatures = expectedNumFeatures;
    wts = loadWeights(initialWtsFile, uniformStartWeights, randomizeStartWeights);
    logger.info("Initial weights: " + wts.toString());

    // Load the tuning set
    tuneSource = IStrings.fileSplitToIStrings(srcFile);
    assert tuneSource.size() > 0;
    loadReferences(tgtFile);
    logger.info(String.format("Intrinsic loss corpus contains %d examples", tuneSource.size()));

    // After loading weights and tuning set, load the optimizer
    // SGD-based optimizers may need the tuning set size or
    // fiddle with the initial weights.
    optimizer = configureOptimizer(optimizerAlg, optimizerFlags);
    logger.info("Loaded optimizer: " + optimizer.toString());
    
    // Load Phrasal
    decoder = loadDecoder(phrasalIniFile);
    logger.info("Loaded Phrasal from: " + phrasalIniFile);
  }
  
  /**
   * Load an instance of phrasal from an ini file.
   * 
   * @param phrasalIniFile
   * @return
   */
  private static Phrasal loadDecoder(String phrasalIniFile) {
    try {
      Map<String, List<String>> config = Phrasal.readConfig(phrasalIniFile);
      Phrasal.initStaticMembers(config);
      Phrasal phrasal = new Phrasal(config);
      FlatPhraseTable.lockIndex();
      return phrasal;
      
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
    } catch (SecurityException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InstantiationException e) {
      e.printStackTrace();
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    } catch (InvocationTargetException e) {
      e.printStackTrace();
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
    throw new RuntimeException("Could not load Phrasal from: " + phrasalIniFile);
  }

  /**
   * Enable n-best list generation for each epoch.
   * 
   * @param writeLists
   */
  public void writeNbest(boolean writeLists) {
    this.writeNbestLists = writeLists;
  }

  /**
   * Return the weight vector from the epoch that maximizes
   * the training objective.
   * 
   * @param b
   */
  public void finalWeightsFromBestEpoch(boolean b) { this.returnBestDev = b; }

  /**
   * Average parameters between epochs.
   * 
   * @param b
   */
  private void doParameterAveraging(boolean b) { this.doParameterAveraging = b; }

  /**
   * Input data to the gradient processor.
   * 
   * @author Spence Green
   *
   */
  private class ProcessorInput {
    public final List<Sequence<IString>> source;
    public final List<List<Sequence<IString>>> references;
    public final int[] translationIds;
    public final Counter<String> weights;
    public final int inputId;
    public final Counter<String> featureWhitelist;
    public ProcessorInput(List<Sequence<IString>> input, 
        List<List<Sequence<IString>>> references, 
        Counter<String> weights, int[] translationIds, int inputId, Counter<String> featureWhitelist) {
      this.source = input;
      this.translationIds = translationIds;
      this.references = references;
      // Copy the weights for the decoder
      this.weights = new OpenAddressCounter<String>(weights, 1.0f);
      this.inputId = inputId;
      this.featureWhitelist = featureWhitelist;
    }
  }

  /**
   * Output of the gradient processor.
   * 
   * @author Spence Green
   *
   */
  private class ProcessorOutput {
    public final Counter<String> gradient;
    public final int inputId;
    public final List<List<RichTranslation<IString, String>>> nbestLists;
    public final int[] translationIds;
    public ProcessorOutput(Counter<String> gradient, 
        int inputId, 
        List<List<RichTranslation<IString, String>>> nbestLists, int[] translationIds) {
      this.gradient = gradient;
      this.inputId = inputId;
      this.nbestLists = nbestLists;
      this.translationIds = translationIds;
    }
  }

  /**
   * Wrapper around the decoder and optimizer for asynchronous online training.
   * 
   * @author Spence Green
   *
   */
  private class GradientProcessor implements ThreadsafeProcessor<ProcessorInput,ProcessorOutput> {
    private final OnlineOptimizer<IString, String> optimizer; 
    private final SentenceLevelMetric<IString, String> lossFunction;
    private final int threadId;

    // Counter for the newInstance() method
    private int childThreadId;

    public GradientProcessor(OnlineOptimizer<IString, String> optimizer, 
        SentenceLevelMetric<IString, String> lossFunction, int firstThreadId) {
      this.optimizer = optimizer;
      this.lossFunction = lossFunction;
      this.threadId = firstThreadId;
      this.childThreadId = firstThreadId+1;
    }

    @Override
    public ProcessorOutput process(ProcessorInput input) {
      assert input.weights != null;
      // Set the decoder weights and decode
      decoder.getScorer(threadId).updateWeights(input.weights);
      
      int batchSize = input.translationIds.length;
      List<List<RichTranslation<IString,String>>> nbestLists = 
          new ArrayList<List<RichTranslation<IString,String>>>(input.translationIds.length);
      Counter<String> gradient;
      if (batchSize == 1) {
        // Online learning with gradient updates for each instance
        List<RichTranslation<IString,String>> nbestList = decoder.decode(input.source.get(0), input.translationIds[0], 
            threadId);
        gradient = optimizer.getGradient(input.weights, input.source.get(0), 
            input.translationIds[0], nbestList, input.references.get(0), lossFunction, input.featureWhitelist);
        nbestLists.add(nbestList);
        
      } else {
        // Mini-batch learning
        for (int i = 0; i < input.translationIds.length; ++i) {
          int translationId = input.translationIds[i];
          Sequence<IString> source = input.source.get(i);
          List<RichTranslation<IString,String>> nbestList = decoder.decode(source, translationId, 
              threadId);
          nbestLists.add(nbestList);
        }
        gradient = optimizer.getBatchGradient(input.weights, input.source, input.translationIds, 
                nbestLists, input.references, lossFunction, input.featureWhitelist);
      }

      return new ProcessorOutput(gradient, input.inputId, nbestLists, input.translationIds);
    }

    @Override
    public ThreadsafeProcessor<ProcessorInput, ProcessorOutput> newInstance() {
      return new GradientProcessor(optimizer, lossFunction, childThreadId++);
    }
  }

  /**
   * Asynchronous template from Langford et al. (2009). Get gradients from the threadpool and update the weight vector.
   * @param threadpool
   * @param updater 
   * @param nbestLists 
   * @param doExpectedBleu 
   * @param timeStep 
   * @param decoderWts 
   * 
   * @return
   */
  private int applyGradientUpdatesTo(Counter<String> currentWts, 
      int updateStep, MulticoreWrapper<ProcessorInput,ProcessorOutput> threadpool, 
      OnlineUpdateRule<String> updater, Map<Integer, List<RichTranslation<IString, String>>> nbestLists, 
      boolean doExpectedBleu) {
    assert threadpool != null;
    assert currentWts != null;
    assert updater != null;
    assert nbestLists != null || !doExpectedBleu;
    
    // There may be more than one gradient available, so loop
    while (threadpool.peek()) {
      final ProcessorOutput result = threadpool.poll();

      // Don't assume that the OnlineOptimizers that compute the gradient will populate the feature
      // index.
      featureIndex.addAll(result.gradient.keySet());
      
      // Apply update rule
      updater.update(currentWts, result.gradient, updateStep);
      
      // Debug info
      logger.info(String.format("Weight update %d with gradient from input step %d (diff: %d)", 
          updateStep, result.inputId, result.inputId - updateStep));
      logger.info(String.format("Weight update %d approximate L2 ||w'-w|| %.4f", updateStep, Counters.L2Norm(result.gradient)));

      ++updateStep;

      // Accumulate for parameter averaging
      if (doParameterAveraging) {
        wts.addAll(currentWts);
      }

      // Add n-best lists from this update step
      if (doExpectedBleu) {
        for (int i = 0; i < result.translationIds.length; ++i) {
          int translationId = result.translationIds[i];
          assert ! nbestLists.containsKey(translationId);
          nbestLists.put(translationId, result.nbestLists.get(i));
        }
      }
    }
    
    return updateStep;
  }

  /**
   * Run an optimization algorithm with a specified loss function. Implements asynchronous updating
   * per Langford et al. (2009).
   * @param batchSize 
   * 
   * @param lossFunction 
   * @param doExpectedBleu 
   * @param randomizeStartingWeights 
   * @param optimizerAlg
   * @param optimizerFlags 
   * @param nThreads
   */
  public void run(int numEpochs, int batchSize, SentenceLevelMetric<IString, String> lossFunction, 
      boolean doExpectedBleu, int weightWriteOutInterval) {
    // Initialize weight vector(s) for the decoder
    // currentWts will be used in every round; wts will accumulate weight vectors
    final int numThreads = decoder.getNumThreads();
    Counter<String> currentWts = new OpenAddressCounter<String>(wts, 1.0f);
    // Clear the global weight vector, which we will use for parameter averaging.
    wts.clear();
    
    final Counter<String> featureWhitelist = new ThreadsafeCounter<String>(expectedNumFeatures);
    final int tuneSetSize = tuneSource.size();
    final int[] indices = ArrayMath.range(0, tuneSetSize);
    final int numBatches = (int) Math.ceil((double) indices.length / (double) batchSize);
    final OnlineUpdateRule<String> updater = optimizer.newUpdater();
    final List<Triple<Double,Integer,Counter<String>>> epochResults = 
        new ArrayList<Triple<Double,Integer,Counter<String>>>(numEpochs);

    logger.info("Start of online tuning");
    logger.info("Number of epochs: " + numEpochs);
    logger.info("Number of threads: " + numThreads);
    logger.info("Number of references: " + references.get(0).size());
    int updateId = 0;
    for (int epoch = 0; epoch < numEpochs; ++epoch) {
      final long startTime = System.nanoTime();
      logger.info("Start of epoch: " + epoch);

      // n-best lists. Purge for each epoch
      Map<Integer,List<RichTranslation<IString, String>>> nbestLists = doExpectedBleu ? 
          new HashMap<Integer,List<RichTranslation<IString, String>>>(tuneSetSize) : null;

      // Threadpool for decoders. Create one per epoch so that we can wait for all jobs
      // to finish at the end of the epoch
      boolean orderResults = false;
      final MulticoreWrapper<ProcessorInput,ProcessorOutput> wrapper = 
          new MulticoreWrapper<ProcessorInput,ProcessorOutput>(numThreads, 
              new GradientProcessor(optimizer,lossFunction,0), orderResults);

      // Randomize order of training examples in-place (Langford et al. (2009), p.4)
      ArrayMath.shuffle(indices);
      logger.info(String.format("Number of batches for epoch %d: %d", epoch, numBatches));
      for (int t = 0; t < numBatches; ++t) {
        int[] batch = makeBatch(indices, t, batchSize);
        int inputId = (epoch*numBatches) + t;
        ProcessorInput input = makeInput(batch, inputId, currentWts, featureWhitelist);
        wrapper.put(input);
        updateId = applyGradientUpdatesTo(currentWts, updateId, wrapper, updater, nbestLists, doExpectedBleu);
        
        if((t+1) % weightWriteOutInterval == 0) {
        	IOTools.writeWeights(String.format("%s.%d.%d.binwts", logPrefix, epoch, t), currentWts);
        }
        // 
        // TODO(spenceg): Extract rules and update phrase table for this example
        //                Be sure to update featureIndex appropriately.
        //                Also need to justify adding features in an online way. Maybe this is
        //                what happens already with stochastic, sparse learning?
      }

      // Wait for threadpool shutdown for this epoch and get final gradients
      wrapper.join();
      updateId = 
          applyGradientUpdatesTo(currentWts, updateId, wrapper, updater, nbestLists, doExpectedBleu);
      
      // Compute (averaged) intermediate weights for next epoch, and write to file.
      if (doParameterAveraging) {
        currentWts = new OpenAddressCounter<String>(wts, 1.0f);
        Counters.divideInPlace(currentWts, (epoch+1)*numBatches);
      }
      IOTools.writeWeights(String.format("%s.%d.binwts", logPrefix, epoch), currentWts);

      // Debug info for this epoch
      long elapsedTime = System.nanoTime() - startTime;
      logger.info(String.format("Epoch %d elapsed time: %.2f seconds", epoch, (double) elapsedTime / 1e9));
      double expectedBleu = 0.0;
      if (doExpectedBleu) {
        expectedBleu = evaluate(currentWts, nbestLists, epoch);
        logger.info(String.format("Epoch %d expected BLEU: %.2f", epoch, expectedBleu));
      }
      // Purge history if we're not picking the best weight vector
      if ( ! returnBestDev) epochResults.clear();
      epochResults.add(new Triple<Double,Integer,Counter<String>>(expectedBleu, epoch, new OpenAddressCounter<String>(currentWts, 1.0f)));
    }
    
    saveFinalWeights(epochResults);
  }

  /**
   * Make a batch from an array of indices.
   * 
   * @param indices
   * @param t
   * @param batchSize
   * @return
   */
  private int[] makeBatch(int[] indices, int t, int batchSize) {
    final int start = t*batchSize;
    assert start < indices.length;
    final int end = Math.min((t+1)*batchSize, indices.length);
    int[] batch = new int[end - start];
    System.arraycopy(indices, start, batch, 0, batch.length);
    return batch;
  }

  /**
   * Make a ProcessorInput object for the thread pool from this mini batch.
   * 
   * @param batch
   * @param featureWhitelist 
   * @param randomizeWeights 
   * @param epoch 
   * @return
   */
  private ProcessorInput makeInput(int[] batch, int inputId, Counter<String> weights, Counter<String> featureWhitelist) {
    List<Sequence<IString>> sourceList = new ArrayList<Sequence<IString>>(batch.length);
    List<List<Sequence<IString>>> referenceList = new ArrayList<List<Sequence<IString>>>(batch.length);
    for (int sourceId : batch) {
      sourceList.add(tuneSource.get(sourceId));
      referenceList.add(references.get(sourceId));
    }
    return new ProcessorInput(sourceList, referenceList, weights, batch, inputId, featureWhitelist);
  }

  /**
   * Calculate BLEU under a weight vector using a set of existing n-best lists.
   * 
   * @param currentWts
   * @param nbestLists
   * @return
   */
  private double evaluate(Counter<String> currentWts,
      Map<Integer, List<RichTranslation<IString, String>>> nbestLists, int epoch) {
    assert currentWts != null && currentWts.size() > 0;
    assert nbestLists.keySet().size() == references.size();

    PrintStream nbestListWriter = writeNbestLists ? 
        IOTools.getWriterFromFile(String.format("%s.%d.nbest", logPrefix, epoch)) : null;

    BLEUMetric<IString, String> bleu = new BLEUMetric<IString, String>(references, false);
    BLEUMetric<IString, String>.BLEUIncrementalMetric incMetric = bleu
        .getIncrementalMetric();
    Scorer<String> scorer = new StaticScorer(currentWts, featureIndex);
    Map<Integer, List<RichTranslation<IString, String>>> sortedMap = 
        new TreeMap<Integer, List<RichTranslation<IString, String>>>(nbestLists);
    for (Map.Entry<Integer, List<RichTranslation<IString, String>>> entry : sortedMap.entrySet()) {
      // Write n-best list to file
      if (nbestListWriter != null) {
        IOTools.writeNbest(entry.getValue(), entry.getKey(), true, nbestListWriter);
      }

      // Score n-best list under current weight vector
      double bestScore = Double.NEGATIVE_INFINITY;
      int bestIndex = Integer.MIN_VALUE;
      int nbestIndex = 0;
      for (RichTranslation<IString, String> translation : entry.getValue()) {
        double score = scorer.getIncrementalScore(translation.features);
        if (score > bestScore) {
          bestScore = score;
          bestIndex = nbestIndex;
        }
        ++nbestIndex;
      }
      incMetric.add(entry.getValue().get(bestIndex));
    }
    
    if (nbestListWriter != null) nbestListWriter.close();

    return incMetric.score() * 100.0;
  }

  /**
   * Load multiple references for accurate expected BLEU evaluation during
   * tuning. Computing BLEU with a single reference is really unstable.
   * 
   * NOTE: This method re-initializes OnlineTuner.references
   * 
   * @param refStr
   */
  public void loadReferences(String refStr) {
    if (refStr == null || refStr.length() == 0) {
      throw new IllegalArgumentException("Invalid reference list");
    }
    
    final int numSourceSentences = tuneSource.size();
    references = new ArrayList<List<Sequence<IString>>>(numSourceSentences);
    String[] filenames = refStr.split(",");
    logger.info("Number of references for expected BLEU calculation: " + filenames.length);
    for (String filename : filenames) {
      List<Sequence<IString>> refList = IStrings.fileSplitToIStrings(filename);
      assert refList.size() == numSourceSentences;
      for (int i = 0; i < numSourceSentences; ++i) {
        if (references.size() <= i) references.add(new ArrayList<Sequence<IString>>(filenames.length));
        references.get(i).add(refList.get(i));
      }
    }
    assert references.size() == numSourceSentences;
  }

  /**
   * Configure weights stored on file.
   * 
   * @param wtsInitialFile
   * @param uniformStartWeights
   * @param randomizeStartWeights 
   * @return
   */
  private Counter<String> loadWeights(String wtsInitialFile,
      boolean uniformStartWeights, boolean randomizeStartWeights) {

    featureIndex = new HashIndex<String>(expectedNumFeatures);
    Counter<String> weights;
    try {
      weights = IOTools.readWeights(wtsInitialFile, featureIndex);
      weights = new OpenAddressCounter<String>(weights, 1.0f);
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException("Could not load weight vector!");
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      throw new RuntimeException("Could not load weight vector!");
    }
    if (uniformStartWeights) {
      // Initialize according to Moses heuristic
      for (String key : weights.keySet()) {
        if (key.startsWith("LM")) {
          weights.setCount(key, 0.5);
        } else if (key.startsWith("WordPenalty")) {
          weights.setCount(key, -1.0);
        } else {
          weights.setCount(key, 0.2);
        }
      }
    }
    if (randomizeStartWeights) {
      double scale = 1e-4;
      OptimizerUtils.randomizeWeightsInPlace(weights, scale);
    }
    return weights;
  }

  /**
   * Configure the tuner for the specific tuning algorithm. Return the optimizer object.
   * 
   * @param optimizerAlg
   * @param optimizerFlags
   * @return
   */
  private OnlineOptimizer<IString, String> configureOptimizer(String optimizerAlg, String[] optimizerFlags) {
    assert optimizerAlg != null;

    if (optimizerAlg.equals("mira-1best")) {
      return new MIRA1BestHopeFearOptimizer(optimizerFlags);

    } else if (optimizerAlg.equals("pro-sgd")) {
      assert wts != null : "You must load the initial weights before loading PairwiseRankingOptimizerSGD";
      assert tuneSource != null : "You must load the tuning set before loading PairwiseRankingOptimizerSGD";
      Counters.normalize(wts);
      return new PairwiseRankingOptimizerSGD(featureIndex, tuneSource.size(), expectedNumFeatures, optimizerFlags);

    } else {
      throw new UnsupportedOperationException("Unsupported optimizer: " + optimizerAlg);
    }
  }

  /**
   * Load a loss function from a string key.
   * 
   * @param lossFunctionStr
   * @param lossFunctionOpts 
   * @return
   */
  public static SentenceLevelMetric<IString, String> loadLossFunction(
      String lossFunctionStr, String[] lossFunctionOpts) {
    assert lossFunctionStr != null;

    if (lossFunctionStr.equals("bleu-smooth")) {
      // Lin and Och smoothed BLEU
      int order = lossFunctionOpts == null ? 4 : Integer.parseInt(lossFunctionOpts[0]);
      return new BLEUSmoothGain<IString,String>(order);

    } else if (lossFunctionStr.equals("bleu-chiang")) {
      // Chiang's oracle document and exponential decay
      int order = lossFunctionOpts == null ? 4 : Integer.parseInt(lossFunctionOpts[0]);
      return new BLEUOracleCost<IString,String>(order, false);

    } else if (lossFunctionStr.equals("bleu-cherry")) {
      // Cherry and Foster (2012)
      int order = lossFunctionOpts == null ? 4 : Integer.parseInt(lossFunctionOpts[0]);
      return new BLEUOracleCost<IString,String>(order, true);

    } else {
      throw new UnsupportedOperationException("Unsupported loss function: " + lossFunctionStr);
    }
  }

  /**
   * Select the final weights from epochResults and save to file.
   * 
   * @param epochResults 
   */
  private void saveFinalWeights(List<Triple<Double, Integer, Counter<String>>> epochResults) {
    if (returnBestDev) {
      // Maximize BLEU (training objective)
      Collections.sort(epochResults);
    } 
    Triple<Double, Integer, Counter<String>> selectedEpoch = epochResults.get(epochResults.size()-1);
    Counter<String> finalWeights = selectedEpoch.third();
    String filename = logPrefix + ".final.binwts";
    IOTools.writeWeights(filename, finalWeights);
    logger.info("Wrote final weights to " + filename);
    logger.info(String.format("Final weights from epoch %d: BLEU: %.2f", selectedEpoch.second(), selectedEpoch.first()));
    logger.info(String.format("Non-zero final weights: %d / %d", finalWeights.keySet().size(), featureIndex.size()));
  }

  /**
   * Perform any necessary cleanup.
   */
  public void shutdown() {
    logHandler.close();
    decoder.shutdown();
  }

  /********************************************
   * MAIN METHOD STUFF
   ********************************************/

  /**
   * Command-line parameter specification.
   * 
   * @return
   */
  private static Map<String,Integer> optionArgDefs() {
    Map<String,Integer> optionMap = new HashMap<String,Integer>();
    optionMap.put("uw", 0);
    optionMap.put("rw", 0);
    optionMap.put("e", 1);
    optionMap.put("o", 1);
    optionMap.put("of", 1);
    optionMap.put("m", 1);
    optionMap.put("mf", 1);
    optionMap.put("n", 1);
    optionMap.put("nb", 0);
    optionMap.put("r", 1);
    optionMap.put("bw", 0);
    optionMap.put("a", 0);
    optionMap.put("b", 1);
    optionMap.put("l", 1);
    optionMap.put("ne", 0);
    optionMap.put("ef", 1);
    optionMap.put("wi", 1);
    return optionMap;
  }

  /**
   * Usage string for the main method.
   * 
   * @return
   */
  private static String usage() {
    StringBuilder sb = new StringBuilder();
    String nl = System.getProperty("line.separator");
    sb.append("Usage: java ").append(OnlineTuner.class.getName()).append(" [OPTIONS] src tgt phrasal_ini wts_initial").append(nl);
    sb.append(nl);
    sb.append("Options:").append(nl);
    sb.append("   -uw        : Uniform weight initialization").append(nl);
    sb.append("   -rw        : Randomize starting weights at the start of each epoch").append(nl);
    sb.append("   -e num     : Number of online epochs").append(nl);
    sb.append("   -o str     : Optimizer: [arow,mira-1best]").append(nl);
    sb.append("   -of str    : Optimizer flags (format: CSV list)").append(nl);
    sb.append("   -m str     : Evaluation metric (loss function) for the tuning algorithm (default: bleu-smooth)").append(nl);
    sb.append("   -mf str    : Evaluation metric flags (format: CSV list)").append(nl);
    sb.append("   -n str     : Experiment name").append(nl);
    sb.append("   -nb        : Write n-best lists to file.").append(nl);
    sb.append("   -r str     : Use multiple references (format: CSV list)").append(nl);
    sb.append("   -bw        : Set final weights to the best training epoch.").append(nl);
    sb.append("   -a         : Enable Collins-style parameter averaging between epochs").append(nl);
    sb.append("   -b num     : Mini-batch size (optimizer must support mini-batch learning").append(nl);
    sb.append("   -l level   : Set java.logging level").append(nl);
    sb.append("   -ne        : Disable expected BLEU calculation (saves memory)").append(nl);
    sb.append("   -ef        : Expected # of features").append(nl);
    sb.append("   -wi        : # of minibatches between intermediate weight file writeouts within an epoch").append(nl);
    return sb.toString().trim();
  }

  /**
   * Online optimization for machine translation.
   * 
   * @param args
   */
  public static void main(String[] args) {
    // Parse command-line parameters
    Properties opts = StringUtils.argsToProperties(args, optionArgDefs());
    int numEpochs = PropertiesUtils.getInt(opts, "e", 1);
    String optimizerAlg = opts.getProperty("o", "mira-1best");
    String[] optimizerFlags = opts.containsKey("of") ? opts.getProperty("of").split(",") : null;
    String lossFunctionStr = opts.getProperty("m", "bleu-smooth");
    String[] lossFunctionOpts = opts.containsKey("mf") ? opts.getProperty("mf").split(",") : null;
    String experimentName = opts.getProperty("n", "debug");
    boolean doNbestOutput = PropertiesUtils.getBool(opts, "nb", false);
    boolean uniformStartWeights = PropertiesUtils.getBool(opts, "uw");
    String refStr = opts.getProperty("r", null);
    boolean finalWeightsFromBestEpoch = PropertiesUtils.getBool(opts, "bw", false);
    boolean doParameterAveraging = PropertiesUtils.getBool(opts, "a", false);
    int batchSize = PropertiesUtils.getInt(opts, "b", 1);
    boolean randomizeStartingWeights = PropertiesUtils.getBool(opts, "rw", false);
    OnlineTuner.logLevel = Level.parse(opts.getProperty("l", "INFO"));
    boolean doExpectedBleu = ! PropertiesUtils.getBool(opts, "ne", false);
    int expectedNumFeatures = PropertiesUtils.getInt(opts, "ef", 30);
    int weightWriteOutInterval = PropertiesUtils.getInt(opts, "wi", 10000/batchSize);


    // Parse arguments
    String[] parsedArgs = opts.getProperty("","").split("\\s+");
    if (parsedArgs.length != 4) {
      System.out.println(usage());
      System.exit(-1);
    }
    String srcFile = parsedArgs[0];
    String tgtFile = parsedArgs[1];
    String phrasalIniFile = parsedArgs[2];
    String wtsInitialFile = parsedArgs[3];

    final long startTime = System.nanoTime();
    OnlineTuner.initLogger(experimentName);
    System.out.println("Phrasal Online Tuner");
    System.out.printf("Startup: %s%n", new Date());
    System.out.println("====================");
    for (Entry<String, String> option : PropertiesUtils.getSortedEntries(opts)) {
      System.out.printf(" %s\t%s%n", option.getKey(), option.getValue());
    }
    System.out.println("====================");
    System.out.println();

    // Run optimization
    final SentenceLevelMetric<IString,String> lossFunction = loadLossFunction(lossFunctionStr, lossFunctionOpts);
    OnlineTuner tuner = new OnlineTuner(srcFile, tgtFile, phrasalIniFile, wtsInitialFile, 
        optimizerAlg, optimizerFlags, uniformStartWeights, randomizeStartingWeights,
        expectedNumFeatures);
    if (refStr != null) {
      tuner.loadReferences(refStr);
    }
    tuner.doParameterAveraging(doParameterAveraging);
    tuner.finalWeightsFromBestEpoch(finalWeightsFromBestEpoch);
    tuner.writeNbest(doNbestOutput);
    tuner.run(numEpochs, batchSize, lossFunction, doExpectedBleu, weightWriteOutInterval);
    tuner.shutdown();

    final long elapsedTime = System.nanoTime() - startTime;
    System.out.printf("Elapsed time: %.2f seconds%n", elapsedTime / 1e9);
    System.out.printf("Finished at: %s%n", new Date());
  }
}
