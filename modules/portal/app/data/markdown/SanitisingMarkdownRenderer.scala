package data.markdown

import javax.inject.Inject
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import views.html.MarkdownRenderer


case class SanitisingMarkdownRenderer @Inject() (rawMarkdownRenderer: RawMarkdownRenderer) extends MarkdownRenderer {

  private val safelistStandard: Safelist = Safelist.basic()
    .addAttributes("a", "target", "_blank")
    .addAttributes("a", "class", "external")
    .addAttributes("a", "rel", "nofollow noopener")

  private val safelistStrict: Safelist = Safelist.simpleText()
    .addTags("p", "a")
    .addAttributes("a", "target", "_blank")
    .addAttributes("a", "class", "external")
    .addAttributes("a", "rel", "nofollow noopener")

  private def render(markdown: String): String = rawMarkdownRenderer.render(markdown)

  override def renderMarkdown(markdown: String): String =
    Jsoup.clean(render(markdown), safelistStandard)

  override def renderUntrustedMarkdown(markdown: String): String =
    Jsoup.clean(render(markdown), safelistStrict)

  override def renderTrustedMarkdown(markdown: String): String =
    render(markdown)
}
