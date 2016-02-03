package com.hanhuy.android.irc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view._
import android.widget._
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.common._
import iota._
import Tweaks._

import scala.util.Try

/**
  * @author pfnguyen
  */
object ServerSetupActivity {
  val EXTRA_SERVER = "qicr.extra.SERVER_ID"
  def start(activity: Activity, server: Option[Server] = None): Unit = {
    val intent = new Intent(activity, classOf[ServerSetupActivity])
    server.foreach(s => intent.putExtra(EXTRA_SERVER, s.id))
    activity.startActivity(intent)
  }
}
class ServerSetupActivity extends AppCompatActivity {

  override def onCreate(savedInstanceState: Bundle) = {
    setTheme(if (Settings.get(Settings.DAYNIGHT_MODE)) R.style.SetupTheme_Light else R.style.SetupTheme_Dark)
    super.onCreate(savedInstanceState)
    import ViewGroup.LayoutParams._
    val toolbar = newToolbar
    toolbar.setNavigationIcon(resolveAttr(R.attr.qicrCloseIcon, _.resourceId))
    setSupportActionBar(toolbar)
    val content = c[ViewGroup](l[FrameLayout](
      toolbar.! >>= lp(MATCH_PARENT, actionBarHeight, Gravity.TOP) >>= padding(all = 0),
      w[FrameLayout] >>= id(Id.content) >>= lpK(MATCH_PARENT, MATCH_PARENT)(margins(top = actionBarHeight))
    ) >>= id(android.R.id.content) >>=
      lp(MATCH_PARENT, MATCH_PARENT))
    setContentView(content.perform())
    val fm = getSupportFragmentManager
    if (fm.findFragmentByTag(MainActivity.SERVER_SETUP_FRAGMENT) == null) {
      val fragment = new ServerSetupFragment
      val id = getIntent.getLongExtra(ServerSetupActivity.EXTRA_SERVER, -1)
      if (id != -1) {
        val b = new Bundle(getIntent.getExtras)
        fragment.setArguments(b)
        Config.servers.now.find(_.id == id).foreach(s => getSupportActionBar.setTitle("Edit: " + s.name))
      } else {
        getSupportActionBar.setTitle("Add a Server")
      }
      fm.beginTransaction
        .add(Id.content, fragment, MainActivity.SERVER_SETUP_FRAGMENT)
        .commit()
    }
  }
}

class ServerSetupFragment extends Fragment {
  val manager = IrcManager.init()

  // hack to store text
  var idholder = 0x10001000

  import ViewGroup.LayoutParams._

  def header = {
    new TextView(getActivity, null, android.R.attr.listSeparatorTextViewStyle)
  }

  def label = c[TableRow](w[TextView] >>=
    lpK(WRAP_CONTENT, WRAP_CONTENT)(margins(right = 12.dp)))

  def inputTweaks: Kestrel[EditText] = c[TableRow](kestrel { e: EditText =>
    e.setSingleLine(true)
    e.setId(idholder)
    idholder = idholder + 1
  } >=> lp(0, WRAP_CONTENT, 1))

  lazy val layout = c[ViewGroup](l[ScrollView](
    l[TableLayout](
      IO(header) >>= text("Connection Info"),
      l[TableRow](
        label >>= text("Name"),
        IO(server_name) >>= inputTweaks >>= hint("required") >>= textCapWords
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        label >>= text("Server address"),
        IO(server_host) >>= inputTweaks >>= hint("required") >>= textUri
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        label >>= text("Port"),
        IO(port) >>= inputTweaks >>= hint("Default: 6667") >>= number
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        w[View],
        IO(autoconnect) >>= text("Enable Autoconnect")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        w[View],
        IO(ssl) >>= text("Enable SSL")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      IO(header) >>= text("User Info"),
      l[TableRow](
        label >>= text("Nickname"),
        IO(nickname) >>= inputTweaks >>= hint("required")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        label >>= text("Alt. nick"),
        IO(altnick) >>= inputTweaks >>= hint("Default: <Nickname>_")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        label >>= text("Real name"),
        IO(realname) >>= inputTweaks >>= hint("required") >>= textCapWords
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        w[View],
        IO(sasl) >>= text("SASL authentication")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        label >>= text("Username"),
        IO(username) >>= inputTweaks >>= hint("required")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        label >>= text("Password"),
        IO(password) >>= inputTweaks >>= hint("optional") >>= textPassword
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      IO(header) >>= text("Session Options"),
      l[TableRow](
        label >>= text("Auto join"),
        IO(autojoin) >>= inputTweaks >>= hint("#chan1 key;#chan2")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
      l[TableRow](
        label >>= text("Auto run"),
        IO(autorun) >>= inputTweaks >>= hint("m pfn hi there;")
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT)
    ) >>= lpK(MATCH_PARENT, MATCH_PARENT)(margins(all = 8 dp)) >>=
      kestrel(_.setColumnStretchable(1, true)) >>= padding(all = 16.dp)
  ) >>= lp(MATCH_PARENT, MATCH_PARENT)).perform()

  lazy val server_name = new EditText(getActivity)
  lazy val server_host = new EditText(getActivity)
  lazy val port = new EditText(getActivity)
  lazy val ssl = checkbox
  lazy val autoconnect = checkbox
  lazy val nickname = new EditText(getActivity)
  lazy val altnick = new EditText(getActivity)
  lazy val realname = new EditText(getActivity)
  lazy val username = new EditText(getActivity)
  lazy val password = new EditText(getActivity)
  lazy val autojoin = new EditText(getActivity)
  lazy val autorun = new EditText(getActivity)
  lazy val sasl = checkbox

  val _server: Server = new Server

  def server: Server = {
    val s = _server
    s.name = server_name.getText.toString
    s.hostname = server_host.getText.toString
    s.port = Try(port.getText.toString.toInt).toOption getOrElse 6667
    s.ssl = ssl.isChecked
    s.autoconnect = autoconnect.isChecked
    s.nickname = nickname.getText.toString
    s.altnick = altnick.getText.toString
    s.realname = realname.getText.toString
    s.username = username.getText.toString
    s.password = password.getText.toString
    s.autojoin = autojoin.getText.toString
    s.autorun = autorun.getText.toString
    s.sasl = sasl.isChecked
    _server
  }

  def server_=(s: Server) = {
    _server.copy(s)
    server_name.setText(s.name)
    server_host.setText(s.hostname)
    port.setText("" + s.port)
    ssl.setChecked(s.ssl)
    sasl.setChecked(s.sasl)
    autoconnect.setChecked(s.autoconnect)
    nickname.setText(s.nickname)
    altnick.setText(s.altnick)
    realname.setText(s.realname)
    username.setText(s.username)
    password.setText(s.password)
    autojoin.setText(s.autojoin.getOrElse(""))
    autorun.setText(s.autorun.getOrElse(""))
  }

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.server_setup_menu, menu)

  override def onOptionsItemSelected(item: MenuItem) = {
    item.getItemId match {
      case R.id.save_server =>
        val s = server
        if (s.valid) {
          if (s.id == -1)
            manager.addServer(s)
          else
            manager.updateServer(s)
          getActivity.finish()
          hideIME()
        } else {
          Toast.makeText(getActivity, R.string.server_incomplete,
            Toast.LENGTH_SHORT).show()
        }
        true
      case android.R.id.home =>
        getActivity.finish()
        hideIME()
        true
      case _ => false
    }
  }

  override def onCreateView(inflater: LayoutInflater,
                            container: ViewGroup, bundle: Bundle): View = {
    val l = layout
    for {
      args <- getArguments.?
      id = args.getLong(ServerSetupActivity.EXTRA_SERVER, -1)
      s <- Config.servers.now.find(_.id == id) if id != -1
    } {
      if (bundle == null)
        server = s
    }
    l
  }

}
