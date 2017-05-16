package tudarmstadt.lt.wsd.server;

import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordLemmatizer;
import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordParser;
import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordSegmenter;
import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.jobimtext.contextualization.annotator.SenseAnnotatorTWSI_test;
import org.jobimtext.contextualization.annotator.TargetAnnotator;
import org.jobimtext.holing.annotator.DependencyAnnotator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.apache.uima.fit.util.JCasUtil.select;

/**
 * Analyzer class that performs NLP operations using UIMA AnalysisEngines.
 */
public class Analyzer {

    private AnalysisEngine targetSetter;
    private JCas cas;
    private AnalysisEngine tokenizer;
    private AnalysisEngine parser;
    private AnalysisEngine lemmatizer;
    private AnalysisEngine jobimAnnotator;
    private AnalysisEngine senseAnnotatorCoarse;
    private AnalysisEngine senseAnnotatorMedium;
    private AnalysisEngine senseAnnotatorFine;

    private final String language;

    /**
     * Constructor; initializes the UIMA pipeline and the CAS.
     */
    public Analyzer() {
        // build annotation engine
        try {
            tokenizer = AnalysisEngineFactory.createEngine(StanfordSegmenter.class);
            parser = AnalysisEngineFactory.createEngine(StanfordParser.class,
                    StanfordParser.PARAM_VARIANT,  "pcfg",
                    StanfordParser.PARAM_WRITE_POS, true,
                    StanfordParser.PARAM_MODEL_LOCATION, "lib/stanford-englishPCFG.ser.gz");
            lemmatizer = AnalysisEngineFactory.createEngine(StanfordLemmatizer.class);
            jobimAnnotator = AnalysisEngineFactory.createEngine(DependencyAnnotator.class);
            senseAnnotatorCoarse = AnalysisEngineFactory.createEngine(SenseAnnotatorTWSI_test.class,
                    SenseAnnotatorTWSI_test.PARAM_EXTRACTOR_CONFIGURATION_FILE, "resources/extractor_parsed_np.xml",
                    SenseAnnotatorTWSI_test.PARAM_SENTENCE_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
                    SenseAnnotatorTWSI_test.PARAM_TOKEN_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
                    SenseAnnotatorTWSI_test.PARAM_JOBIM_ANNOTATION, "org.jobimtext.holing.type.JoBim",
                    SenseAnnotatorTWSI_test.PARAM_DT_CONFIG, "resources/conf_mysql_stanford_n200.xml",
                    SenseAnnotatorTWSI_test.PARAM_DCA_CONFIG, "resources/dca_config.xml",
                    SenseAnnotatorTWSI_test.PARAM_SET_LARGEST_SENSE, false,
                    SenseAnnotatorTWSI_test.PARAM_MAX_BIMS, 3
                    );
            senseAnnotatorMedium= AnalysisEngineFactory.createEngine(SenseAnnotatorTWSI_test.class,
                    SenseAnnotatorTWSI_test.PARAM_EXTRACTOR_CONFIGURATION_FILE, "resources/extractor_parsed_np.xml",
                    SenseAnnotatorTWSI_test.PARAM_SENTENCE_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
                    SenseAnnotatorTWSI_test.PARAM_TOKEN_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
                    SenseAnnotatorTWSI_test.PARAM_JOBIM_ANNOTATION, "org.jobimtext.holing.type.JoBim",
                    SenseAnnotatorTWSI_test.PARAM_DT_CONFIG, "resources/conf_mysql_stanford_n100.xml",
                    SenseAnnotatorTWSI_test.PARAM_DCA_CONFIG, "resources/dca_config.xml",
                    SenseAnnotatorTWSI_test.PARAM_SET_LARGEST_SENSE, false,
                    SenseAnnotatorTWSI_test.PARAM_MAX_BIMS, 3
            );
            senseAnnotatorFine= AnalysisEngineFactory.createEngine(SenseAnnotatorTWSI_test.class,
                    SenseAnnotatorTWSI_test.PARAM_EXTRACTOR_CONFIGURATION_FILE, "resources/extractor_parsed_np.xml",
                    SenseAnnotatorTWSI_test.PARAM_SENTENCE_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
                    SenseAnnotatorTWSI_test.PARAM_TOKEN_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
                    SenseAnnotatorTWSI_test.PARAM_JOBIM_ANNOTATION, "org.jobimtext.holing.type.JoBim",
                    SenseAnnotatorTWSI_test.PARAM_DT_CONFIG, "resources/conf_mysql_stanford_n50.xml",
                    SenseAnnotatorTWSI_test.PARAM_DCA_CONFIG, "resources/dca_config.xml",
                    SenseAnnotatorTWSI_test.PARAM_SET_LARGEST_SENSE, false,
                    SenseAnnotatorTWSI_test.PARAM_MAX_BIMS, 3
            );
        } catch (ResourceInitializationException e) {
            e.printStackTrace();
        }
        // build cas
        try {
            cas = JCasFactory.createJCas();
        } catch (UIMAException e) {
            e.printStackTrace();
        }
        language = "en";
    }

    /**
     * Constructor; initializes the UIMA pipeline and the CAS, then processes an input text
     * @param input input text that is analyzed in the CAS
     */
    public Analyzer(String input) {
        this();
        processText(input);
    }

    /**
     * Processes a new text by the NLP pipeline. Resets the CAS for fast processing.
     * @param input input text
     */
    public void processText(String input) {
        preprocess(input);

        ArrayList<String> words = new ArrayList<>();
        for (Annotation a: JCasUtil.select(cas, POS.class)) {
            POS p = (POS) a;
            if (p.getPosValue().startsWith("N")) {
                words.add(a.getCoveredText());
            }
        }


        try {
            targetSetter = AnalysisEngineFactory.createEngine(TargetAnnotator.class,
                    TargetAnnotator.PARAM_SENTENCE_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
                    TargetAnnotator.PARAM_TOKEN_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
                    TargetAnnotator.PARAM_TARGETWORDS, words);
            targetSetter.process(cas);
            senseAnnotatorCoarse.process(cas);
        } catch (AnalysisEngineProcessException e) {
            e.printStackTrace();
        } catch (ResourceInitializationException e) {
            e.printStackTrace();
        }
    }


    public void processText(String input, String targetWord) {
        preprocess(input);
        ArrayList<String> words = new ArrayList<>();
        words.add(targetWord);
        try {
            targetSetter = AnalysisEngineFactory.createEngine(TargetAnnotator.class,
                    TargetAnnotator.PARAM_SENTENCE_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
                    TargetAnnotator.PARAM_TOKEN_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
                    TargetAnnotator.PARAM_TARGETWORDS, words);
            targetSetter.process(cas);
            senseAnnotatorCoarse.process(cas);
        } catch (AnalysisEngineProcessException e) {
            e.printStackTrace();
        } catch (ResourceInitializationException e) {
            e.printStackTrace();
        }
    }

    public void processText(String input, String targetWord, String model) {
        preprocess(input);
        ArrayList<String> words = new ArrayList<>();
        words.add(targetWord);
        try {
            targetSetter = AnalysisEngineFactory.createEngine(TargetAnnotator.class,
                    TargetAnnotator.PARAM_SENTENCE_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence",
                    TargetAnnotator.PARAM_TOKEN_ANNOTATION, "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token",
                    TargetAnnotator.PARAM_TARGETWORDS, words);
            targetSetter.process(cas);
            if (model.compareTo("medium") == 0) {
                senseAnnotatorMedium.process(cas);
            } else if (model.compareTo("fine") == 0) {
                senseAnnotatorFine.process(cas);
            } else {
                senseAnnotatorCoarse.process(cas);
            }
        } catch (AnalysisEngineProcessException e) {
            e.printStackTrace();
        } catch (ResourceInitializationException e) {
            e.printStackTrace();
        }
    }



    private void preprocess(String input) {
        createCas(input);
        try {
            tokenizer.process(cas);
            parser.process(cas);
            lemmatizer.process(cas);
            jobimAnnotator.process(cas);
        } catch (AnalysisEngineProcessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the CAS from an input text
     * @param input input text
     */
    private void createCas(String input) {
        cas.reset();
        cas.setDocumentText(input);
        cas.setDocumentLanguage(language);
    }

    /**
     * Retrieves the CAS
     * @return the CAS object
     */
    public JCas getCas() {
        return cas;
    }

    /**
     * Retrieves a list of tokens as Strings from a provided CAS.
     * @param cas the CAS, from which the tokens are extracted
     * @return a list of Strings
     */
    public List<String> getTokenStrings(JCas cas) {
        List<String> tokenStrings = new ArrayList<>();
        Collection<Token> tokens = select(cas, Token.class);
        for (Annotation token : tokens) {
            tokenStrings.add(token.getCoveredText());
        }
        return tokenStrings;
    }

    /**
     * Retrieves a list of tokens as Strings from the current CAS.
     * @return a list of Strings
     */
    public List<String> getTokenStrings() {
        return getTokenStrings(this.cas);
    }

    public void setTargetWord(String targetWord) {
        //targetSetter.set
    }
}
