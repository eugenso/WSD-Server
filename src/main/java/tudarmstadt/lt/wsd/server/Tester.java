package tudarmstadt.lt.wsd.server;

import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.jobimtext.twsi.TargetWord;
import org.jobimtext.type.SenseInformation;

/**
 * Created by eugen on 4/6/17.
 */
public class Tester {

    public static void main(String[] args) {
        String text = "the mice tissue was developing a cancer";
        String target = "mice";
        Analyzer analyzer = new Analyzer();

        analyzer.processText(text, target);

        JCas cas = analyzer.getCas();
        for (Annotation tw : JCasUtil.select(cas, TargetWord.class)) {
            System.out.println("_________________________________");
            for (Annotation a : JCasUtil.selectCovered(cas, SenseInformation.class, tw)) {

                SenseInformation si = (SenseInformation) a;
                System.out.println(si.getCoveredText() + ":" + si.getSenseId() + "\t" + si.getContextScore() + "\t" + si.getLmScore() + "\t" + si.getRank());
                System.out.println(si.getClusterElements());
                System.out.println(si.getHypernyms());
                System.out.println(si.getMatchedFeatures());

            }
        }
    }

}
