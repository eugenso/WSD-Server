package tudarmstadt.lt.wsd.server;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.util.JSONUtils;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.jobimtext.api.struct.DCAThesaurusDatastructure;
import org.jobimtext.api.struct.DatabaseThesaurusDatastructure;
import org.jobimtext.api.struct.IThesaurusDatastructure;
import org.jobimtext.api.struct.Order1;
import org.jobimtext.holing.extractor.JobimAnnotationExtractor;
import org.jobimtext.holing.extractor.JobimExtractorConfiguration;
import org.jobimtext.holing.type.JoBim;
import org.jobimtext.twsi.TargetWord;
import org.jobimtext.type.SenseInformation;
import org.mortbay.util.ajax.JSON;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.xml.bind.JAXBException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;



@RestController
@EnableAutoConfiguration
@Configuration
@ComponentScan
public class ApplicationController {

    private static IThesaurusDatastructure dtCoarse;
    private static IThesaurusDatastructure dtMedium;
    private static IThesaurusDatastructure dtFine;
    private static DCAThesaurusDatastructure dt_dca;
    private static Analyzer analyzer;
    private static JobimAnnotationExtractor extractor;


    @CrossOrigin(origins = "*", allowedHeaders = "*", methods = RequestMethod.POST)
    @RequestMapping(value = "/predictWordSense", method = RequestMethod.POST)
    synchronized String predictWordSense(@Valid @RequestBody String jsonBody) {
        RequestData req = new RequestData(jsonBody);
        String context = req.getContext().replaceAll("\n", " ").replaceAll("\r", " ");
        String word = req.getWord();
        String modelName = req.getModelName();

        if (modelName.compareTo("depslm5") == 0) {
            analyzer.processText(context, word, "fine");
        } else if (modelName.compareTo("depslm3") == 0) {
            analyzer.processText(context, word, "medium");
        } else {
            analyzer.processText(context, word, "coarse");
        }
        JCas cas = analyzer.getCas();



        JSONObject out = getJSONResponse(cas, word);

        return JSONUtils.valueToString(out, 4, 1);
    }

    private JSONObject getJSONResponse(JCas cas, String word) {
        JSONObject out = new JSONObject();

        out.put("context", cas.getDocumentText());
        out.put("word", word);
        out.put("modelName", "depslm");

        Set<String> features;
        for (Annotation tw : JCasUtil.select(cas, TargetWord.class)) {
            // get context features
            features = new HashSet<>();
            String lemma = word;
            for (Annotation jb : JCasUtil.selectCovered(JoBim.class, tw)) {
                JoBim b = (JoBim) jb;
                lemma = extractor.extractKey(b);
                features.add(extractor.extractValues(b));
            }
            out.put("contextFeatures", features);



            // predictions
            JSONArray predictions = new JSONArray();
            ArrayList<SenseInformation> orderedPredictions = getOrderedPredictions(cas, tw);
            for (SenseInformation si : orderedPredictions) {

                // prediction
                JSONObject prediction = new JSONObject();
                prediction.put("simScore", si.getLmScore());
                // ranks start at '0'
                prediction.put("rank", si.getRank()-1);

                JSONObject sCluster = new JSONObject();
                sCluster.put("id", si.getIdentifier()+"#"+si.getSenseId());
                sCluster.put("lemma", lemma);

                // cluster terms
                JSONArray words = new JSONArray();
                JSONArray wordsBare = new JSONArray();
                for (int i=0; i< si.getClusterElements().size(); i++) {
                    wordsBare.add(removePOS(si.getClusterElements(i)));
                    words.add(si.getClusterElements(i));
                }
                sCluster.put("words", wordsBare);

                //  hypernyms
                JSONArray hypernyms = new JSONArray();
                for (int i=0; i< si.getHypernyms().size(); i++) {
                    hypernyms.add(si.getHypernyms(i).substring(0, si.getHypernyms(i).lastIndexOf(":")));
                }
                sCluster.put("hypernyms", hypernyms);

                //  mutual features
                JSONArray mutual = new JSONArray();
                for (int i=0; i< si.getMatchedFeatures().size(); i++) {
                    JSONObject mf = new JSONObject();
                    int sepPosition = si.getMatchedFeatures(i).lastIndexOf(":");
                    if (sepPosition == -1) {
                        continue;
                    }

                    mf.put("label", si.getMatchedFeatures(i).substring(0, sepPosition));
                    mf.put("weight", Double.parseDouble(si.getMatchedFeatures(i).substring(sepPosition+1)));
                    mutual.add(mf);
                }
                prediction.put("mutualFeatures", mutual);

                prediction.put("confidenceProb", si.getContextScore());

                // get cluster features
                int numClusterFeatures = 0;
                Set<Order1> contextsSet = new HashSet<>();
                for (Object rel : words) {
                    List<Order1> contexts = dt_dca.getTermContextsScores(rel.toString());
                    numClusterFeatures += contexts.size();
                    if (contexts.size() < 5) {
                        contextsSet.addAll(contexts);
                    } else {
                        contextsSet.addAll(contexts.subList(0, 5));
                    }
                }

                // top 20 features
                HashMap<String, Double> contexts = new HashMap<>();
                for (Order1 f : contextsSet) {
                    contexts.put(f.key, f.score);
                }

                // sort contexts descending by value
                LinkedHashMap<String, Double> sortedContexts =
                        contexts.entrySet().stream().
                                sorted(Entry.comparingByValue(Comparator.reverseOrder())).
                                collect(Collectors.toMap(Entry::getKey, Entry::getValue,
                                        (e1, e2) -> e1, LinkedHashMap::new));


                prediction.put("contextFeatures", getJSONArray(sortedContexts, -1));
                prediction.put("top20ClusterFeatures", getJSONArray(sortedContexts, 20));


                prediction.put("numClusterFeatures", numClusterFeatures);

                prediction.put("senseCluster", sCluster);
                predictions.add(prediction);

            }
            out.put("predictions", predictions);

        }
        return out;
    }

    private ArrayList<SenseInformation> getOrderedPredictions(JCas cas, Annotation tw) {
        ArrayList<SenseInformation> orderedPredictions = new ArrayList<>();
        List<SenseInformation> predAnnotations = JCasUtil.selectCovered(cas, SenseInformation.class, tw);
        int max = predAnnotations.size();
        int c = 0;
        while (c++ < max) {
            orderedPredictions.add(new SenseInformation(cas));
        }
        for (Annotation a : predAnnotations) {
            SenseInformation senseInformation = (SenseInformation) a;
            orderedPredictions.set(senseInformation.getRank()-1, senseInformation);
        }
        return orderedPredictions;
    }


    private JSONArray getJSONArray(LinkedHashMap<String, Double> sortedContexts, int limit) {
        JSONArray ret = new JSONArray();

        Set<String> keySet = sortedContexts.keySet();
        for (String key : keySet) {
            if (limit-- == 0) {
                return ret;
            }
            JSONObject e = new JSONObject();
            e.put("label", removePOSDep(key));
            e.put("weight", sortedContexts.get(key));
            ret.add(e);
        }
        return ret;
    }

    private String removePOSDep(String dep) {
        String[] split = dep.split("#");
        if (split.length >2) {

            return split[0] + "#" + split[2];
        }
        return dep;
    }

    private String removePOS(String term) {
        String[] split = term.split("#");
        if (split.length >1) {

            return split[0];
        }
        return term;
    }

    /**
     * Runs the RESTful server.
     *
     * @param args execution arguments
     */
    public static void main(String[] args) throws InstantiationException {

        analyzer = new Analyzer();
        try {
            extractor = JobimExtractorConfiguration.getExtractorFromXmlFile("resources/extractor_parsed_np.xml");
        } catch (JAXBException | IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        //dtCoarse = new WebThesaurusDatastructure("resources/conf_web_enNews_stanford.xml");
        dtCoarse = new DatabaseThesaurusDatastructure("resources/conf_mysql_stanford_n200.xml");
        dtCoarse.connect();

        dtMedium = new DatabaseThesaurusDatastructure("resources/conf_mysql_stanford_n100.xml");
        dtMedium.connect();

        dtFine = new DatabaseThesaurusDatastructure("resources/conf_mysql_stanford_n50.xml");
        dtFine.connect();


        dt_dca = new DCAThesaurusDatastructure("resources/dca_config.xml");
        dt_dca.connect();

        SpringApplication.run(ApplicationController.class, args);
    }

    public class RequestData {

        String context;
        String word;

        String modelName;

        RequestData(String context, String word, String featureType) {
            this.context = context;
            this.word = word;
            this.modelName = featureType;
        }

        RequestData(String jsonString) {
            Map<String, String> j = (Map<String, String>) JSON.parse(jsonString);

            this.context = j.get("context");
            this.word = j.get("word");
            this.modelName = j.get("modelName");
            if (modelName == null) {
                this.modelName = j.get("featureType");
            }
        }


        String getContext() {
            return context;
        }

        String getWord() {
            return word;
        }

        String getModelName() {
            return modelName;
        }

    }
}