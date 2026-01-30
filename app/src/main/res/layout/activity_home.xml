<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#101010">

    <LinearLayout
        android:id="@+id/headerLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="12dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <ImageView
            android:id="@+id/ivLogo"
            android:layout_width="120dp"
            android:layout_height="36dp"
            android:src="@drawable/ic_launcher"
            android:scaleType="fitCenter"
            android:layout_marginEnd="12dp" />

        <EditText
            android:id="@+id/etSearch"
            android:layout_width="300dp"
            android:layout_height="36dp"
            android:background="@drawable/bg_search_rounded"
            android:paddingStart="36dp"
            android:paddingEnd="8dp"
            android:drawableStart="@drawable/ic_search"
            android:drawablePadding="8dp"
            android:hint="Buscar..."
            android:textColor="#FFFFFF"
            android:textColorHint="#AAAAAA"
            android:imeOptions="actionSearch"
            android:inputType="textNoSuggestions"
            android:maxLines="1" />

        <View
            android:layout_width="0dp"
            android:layout_height="1dp"
            android:layout_weight="1" />

        <ImageButton
            android:id="@+id/btnSettings"
            android:layout_width="36dp"
            android:layout_height="36dp"
            android:layout_marginStart="12dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@drawable/ic_settings"
            app:tint="#FFFFFF"
            android:focusable="true" />
    </LinearLayout>

    <androidx.cardview.widget.CardView
        android:id="@+id/cardBanner"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintHeight_percent="0.45"
        android:layout_marginHorizontal="12dp"
        android:layout_marginTop="8dp"
        app:cardCornerRadius="14dp"
        app:cardBackgroundColor="#202020"
        app:cardElevation="6dp"
        android:focusable="true"
        android:clickable="true"
        app:layout_constraintTop_toBottomOf="@id/headerLayout"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:nextFocusDown="@id/cardLiveTv">

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <ImageView
                android:id="@+id/imgBanner"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:scaleType="centerCrop"
                android:src="@android:color/transparent" />

            <View
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@drawable/gradient_black_bottom" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom"
                android:padding="16dp"
                android:orientation="vertical">

                <ImageView
                    android:id="@+id/imgBannerLogo"
                    android:layout_width="220dp"
                    android:layout_height="80dp"
                    android:layout_gravity="start"
                    android:scaleType="fitCenter"
                    android:visibility="gone" />

                <TextView
                    android:id="@+id/tvBannerTitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Carregando..."
                    android:textColor="#FFFFFF"
                    android:textSize="22sp"
                    android:textStyle="bold"
                    android:shadowColor="#000000"
                    android:shadowDx="2"
                    android:shadowDy="2"
                    android:shadowRadius="4" />

                <TextView
                    android:id="@+id/tvBannerOverview"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text=""
                    android:textColor="#E0E0E0"
                    android:textSize="13sp"
                    android:maxLines="2"
                    android:ellipsize="end"
                    android:layout_marginTop="4dp" />
            </LinearLayout>
        </FrameLayout>
    </androidx.cardview.widget.CardView>

    <LinearLayout
        android:id="@+id/mainButtonsLayout"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:orientation="horizontal"
        android:padding="12dp"
        android:baselineAligned="false"
        app:layout_constraintTop_toBottomOf="@id/cardBanner"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <androidx.cardview.widget.CardView
            android:id="@+id/cardLiveTv"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:layout_marginEnd="12dp"
            app:cardCornerRadius="16dp"
            app:cardElevation="8dp"
            android:clickable="true"
            android:focusable="true"
            app:cardBackgroundColor="@android:color/transparent"
            android:nextFocusUp="@id/cardBanner"
            android:nextFocusRight="@id/cardMovies">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:orientation="vertical"
                android:background="@drawable/bg_premium_blue">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="📺"
                    android:textSize="32sp"
                    android:layout_marginBottom="6dp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="AO VIVO"
                    android:textColor="#FFFFFF"
                    android:textSize="16sp"
                    android:textStyle="bold"/>
            </LinearLayout>
        </androidx.cardview.widget.CardView>

        <androidx.cardview.widget.CardView
            android:id="@+id/cardMovies"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:layout_marginHorizontal="6dp"
            app:cardCornerRadius="16dp"
            app:cardElevation="8dp"
            android:clickable="true"
            android:focusable="true"
            app:cardBackgroundColor="@android:color/transparent"
            android:nextFocusUp="@id/cardBanner"
            android:nextFocusLeft="@id/cardLiveTv"
            android:nextFocusRight="@id/cardSeries">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:orientation="vertical"
                android:background="@drawable/bg_premium_blue">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="🎬"
                    android:textSize="32sp"
                    android:layout_marginBottom="6dp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="FILMES"
                    android:textColor="#FFFFFF"
                    android:textSize="16sp"
                    android:textStyle="bold"/>
            </LinearLayout>
        </androidx.cardview.widget.CardView>

        <androidx.cardview.widget.CardView
            android:id="@+id/cardSeries"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:layout_marginHorizontal="6dp"
            app:cardCornerRadius="16dp"
            app:cardElevation="8dp"
            android:clickable="true"
            android:focusable="true"
            app:cardBackgroundColor="@android:color/transparent"
            android:nextFocusUp="@id/cardBanner"
            android:nextFocusLeft="@id/cardMovies"
            android:nextFocusRight="@id/cardKids">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:orientation="vertical"
                android:background="@drawable/bg_premium_blue">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="🍿"
                    android:textSize="32sp"
                    android:layout_marginBottom="6dp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="SÉRIES"
                    android:textColor="#FFFFFF"
                    android:textSize="16sp"
                    android:textStyle="bold"/>
            </LinearLayout>
        </androidx.cardview.widget.CardView>

        <androidx.cardview.widget.CardView
            android:id="@+id/cardKids"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            app:cardCornerRadius="16dp"
            app:cardElevation="10dp"
            android:clickable="true"
            android:focusable="true"
            app:cardBackgroundColor="@android:color/transparent"
            android:nextFocusUp="@id/cardBanner"
            android:nextFocusLeft="@id/cardSeries">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:orientation="vertical"
                android:background="@drawable/selector_kids_premium">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="🎨"
                    android:textSize="32sp"
                    android:layout_marginBottom="6dp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="KIDS"
                    android:textColor="#FFFFFF"
                    android:textSize="16sp"
                    android:textStyle="bold"/>
            </LinearLayout>
        </androidx.cardview.widget.CardView>

    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
