package tudarmstadt.lt.wsd.server;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.util.JSONUtils;
import org.apache.pig.impl.util.Pair;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.jobimtext.api.struct.*;
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
    String predictWordSense(@Valid @RequestBody String jsonBody) {
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

        //JSONObject out = getJSONResponse(req, context, target, targetNP);

        return JSONUtils.valueToString(out, 4, 1);
    }

    private JSONObject getJSONResponse(JCas cas, String word) {
        JSONObject out = new JSONObject();

        out.put("context", cas.getDocumentText());
        out.put("word", word);
        out.put("modelName", "depslm");

        Set<String> features;
        for (Annotation tw : JCasUtil.select(cas, TargetWord.class)) {
            // get context featues
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
            for (Annotation a : JCasUtil.selectCovered(cas, SenseInformation.class, tw)) {
                SenseInformation si = (SenseInformation) a;


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
                for (int i=0; i< si.getClusterElements().size(); i++) {
                    words.add(si.getClusterElements(i));
                }
                sCluster.put("words", words);

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

                    mf.put("label", si.getMatchedFeatures(i).substring(0, sepPosition));
                    mf.put("weight", si.getMatchedFeatures(i).substring(sepPosition+1));
                    mutual.add(mf);
                }
                sCluster.put("mutualFeatures", mutual);

                out.put("contextFeatures", features);

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

/*    private JSONObject getJSONResponse(RequestData req, String context) {
        JSONObject out = new JSONObject();
        out.put("context", context);
        out.put("word", req.getWord());
        String target = req.getWord().concat("#NN");
        String targetNP = req.getWord().concat("#NP");

        // context features
        List<Pair<String, List<String>>> holing = dtCoarse.getSentenceHoling(context);
        List<String> features = null;
        for (Pair<String, List<String>> h : holing) {
            if (h.first.compareTo(target) == 0 || h.first.compareTo(targetNP) == 0) {
                features = h.second;
            }
        }
        out.put("contextFeatures", features);

        // fill sense columns with predictions
        JSONArray predictions = new JSONArray();
        List<Sense> senses = dtCoarse.getIsas(target);

        for (Sense s : senses) {
            JSONObject sCluster = new JSONObject();
            sCluster.put("id", target + "#" + s.getCui());
            sCluster.put("lemma", req.getWord());

            // hypernyms
            JSONArray hypernyms = new JSONArray();
            for (String h : s.getIsas()) {
                String[] entry = h.split(":");
                JSONArray hEntry = new JSONArray();
                hEntry.add(entry[0]);
                hEntry.add(entry[1]);
                hypernyms.add(hEntry);
            }
            sCluster.put("weighted_hypernyms", hypernyms);

            // related words (adds 10 examples)
            JSONArray related = new JSONArray();
            related.addAll(s.getSenses());
            sCluster.put("words", related);

            // context features for a sense
            int numClusterFeatures = 0;
            Set<Order1> contextsSet = new HashSet<>();
            for (Object rel : related) {
                List<Order1> contexts = dtCoarse.getTermContextsScores(rel.toString(), 200.00);
                numClusterFeatures += contexts.size();
                if (contexts.size() < 10) {
                    contextsSet.addAll(contexts);
                } else {
                    contextsSet.addAll(contexts.subList(0, 10));
                }
            }

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

            JSONObject pred = new JSONObject();
            pred.put("senseCluster", sCluster);
            pred.put("simScore", 0.00);
            // XXXX
            pred.put("rank", 1);
            // LM Score
            pred.put("confidenceProb", 1.00);
            // XXXX
            pred.put("mutualFeatures", null);
            pred.put("contextFeatures", getJSONArray(sortedContexts, -1));
            pred.put("top20ClusterFeatures", getJSONArray(sortedContexts, 20));
            pred.put("numClusterFeatures", numClusterFeatures);

            predictions.add(pred);
        }
        out.put("predictions", predictions);
        return out;
    }*/

    private JSONArray getJSONArray(LinkedHashMap<String, Double> sortedContexts, int limit) {
        JSONArray ret = new JSONArray();

        Set<String> keySet = sortedContexts.keySet();
        for (String key : keySet) {
            if (limit-- == 0) {
                return ret;
            }
            JSONObject e = new JSONObject();
            e.put("label", key);
            e.put("weight", sortedContexts.get(key));
            ret.add(e);
        }
        return ret;
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